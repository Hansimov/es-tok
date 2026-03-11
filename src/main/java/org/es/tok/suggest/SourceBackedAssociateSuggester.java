package org.es.tok.suggest;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.SourceFieldMetrics;
import org.elasticsearch.search.lookup.Source;
import org.elasticsearch.search.lookup.SourceProvider;
import org.es.tok.suggest.LuceneIndexSuggester.CompletionConfig;
import org.es.tok.suggest.LuceneIndexSuggester.SuggestionOption;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SourceBackedAssociateSuggester {

    public List<SuggestionOption> suggestAssociate(
            Engine.Searcher searcher,
            IndexService indexService,
            Collection<String> fields,
            String text,
            CompletionConfig config) throws IOException {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<FieldContext> fieldContexts = resolveFieldContexts(indexService, fields);
        if (fieldContexts.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> seedTerms = analyzeSeedTerms(fieldContexts, text);
        if (seedTerms.isEmpty()) {
            seedTerms.add(normalizeToken(text));
        }

        BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();
        for (FieldContext fieldContext : fieldContexts) {
            for (String seedTerm : seedTerms) {
                if (!seedTerm.isEmpty()) {
                    queryBuilder.add(new TermQuery(new Term(fieldContext.indexField(), seedTerm)), BooleanClause.Occur.SHOULD);
                }
            }
        }
        BooleanQuery query = queryBuilder.build();
        if (query.clauses().isEmpty()) {
            return List.of();
        }

        TopDocs topDocs = searcher.search(query, config.scanLimit());
        if (topDocs.scoreDocs.length == 0) {
            return List.of();
        }

        SourceProvider sourceProvider = SourceProvider.fromLookup(
                indexService.mapperService().mappingLookup(),
                null,
                SourceFieldMetrics.NOOP);
        Map<String, AssociateAccumulator> candidates = new HashMap<>();
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        for (int rank = 0; rank < topDocs.scoreDocs.length; rank++) {
            ScoreDoc scoreDoc = topDocs.scoreDocs[rank];
            int leafIndex = ReaderUtil.subIndex(scoreDoc.doc, leaves);
            LeafReaderContext leaf = leaves.get(leafIndex);
            int leafDocId = scoreDoc.doc - leaf.docBase;
            Source source = sourceProvider.getSource(leaf, leafDocId);
            collectCandidatesFromSource(candidates, fieldContexts, source, seedTerms, scoreDoc.score, rank);
        }

        return candidates.values().stream()
                .sorted(AssociateAccumulator.ORDER)
                .limit(config.size())
                .map(AssociateAccumulator::toSuggestion)
                .toList();
    }

    private LinkedHashSet<String> analyzeSeedTerms(List<FieldContext> fieldContexts, String text) throws IOException {
        LinkedHashSet<String> seedTerms = new LinkedHashSet<>();
        for (FieldContext fieldContext : fieldContexts) {
            seedTerms.addAll(analyze(fieldContext.analyzer(), fieldContext.indexField(), text));
        }
        seedTerms.remove("");
        return seedTerms;
    }

    private void collectCandidatesFromSource(
            Map<String, AssociateAccumulator> candidates,
            List<FieldContext> fieldContexts,
            Source source,
            Set<String> seedTerms,
            float hitScore,
            int rank) throws IOException {
        float docWeight = (float) ((Math.log1p(Math.max(1.0f, hitScore)) + 1.0d) / (1.0d + (rank * 0.08d)));
        Set<String> seenInDoc = new LinkedHashSet<>();
        for (FieldContext fieldContext : fieldContexts) {
            Object rawValue = source.extractValue(fieldContext.sourcePath(), null);
            for (String value : flattenSourceValues(rawValue)) {
                for (String token : analyze(fieldContext.analyzer(), fieldContext.indexField(), value)) {
                    if (!isAcceptableAssociateCandidate(token, seedTerms)) {
                        continue;
                    }
                    if (!seenInDoc.add(token)) {
                        continue;
                    }
                    candidates.computeIfAbsent(token, AssociateAccumulator::new)
                            .add(docWeight, fieldContext.indexField());
                }
            }
        }
    }

    private List<FieldContext> resolveFieldContexts(IndexService indexService, Collection<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        Map<String, FieldContext> resolved = new LinkedHashMap<>();
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            String sourcePath = sourcePath(field);
            MappedFieldType fieldType = indexService.mapperService().fieldType(field);
            if (fieldType == null) {
                fieldType = indexService.mapperService().fieldType(sourcePath);
            }
            Analyzer analyzer = fieldType != null ? fieldType.getTextSearchInfo().searchAnalyzer() : Lucene.KEYWORD_ANALYZER;
            resolved.put(field, new FieldContext(field, sourcePath, analyzer));
        }
        return List.copyOf(resolved.values());
    }

    private static String sourcePath(String field) {
        if (field.endsWith(".words")) {
            return field.substring(0, field.length() - ".words".length());
        }
        if (field.endsWith(".suggest")) {
            return field.substring(0, field.length() - ".suggest".length());
        }
        return field;
    }

    private static List<String> flattenSourceValues(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String stringValue) {
            return List.of(stringValue);
        }
        if (value instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>();
            for (Object item : collection) {
                values.addAll(flattenSourceValues(item));
            }
            return values;
        }
        return List.of(value.toString());
    }

    private static List<String> analyze(Analyzer analyzer, String field, String text) throws IOException {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        try (TokenStream tokenStream = analyzer.tokenStream(field, text)) {
            CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                String normalized = normalizeToken(termAttribute.toString());
                if (!normalized.isEmpty()) {
                    tokens.add(normalized);
                }
            }
            tokenStream.end();
        }
        return tokens;
    }

    private static boolean isAcceptableAssociateCandidate(String token, Set<String> seedTerms) {
        if (token == null || token.isBlank() || seedTerms.contains(token)) {
            return false;
        }
        if (token.indexOf(' ') >= 0) {
            return false;
        }
        int codePointLength = token.codePointCount(0, token.length());
        if (codePointLength == 1 && isFunctionWord(token.codePointAt(0))) {
            return false;
        }
        if (codePointLength > 24) {
            return false;
        }
        boolean hasMeaningfulLetter = token.chars().anyMatch(ch -> Character.isLetter(ch) || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN);
        if (!hasMeaningfulLetter) {
            return false;
        }
        return token.chars().anyMatch(ch -> Character.isLetterOrDigit(ch) || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN);
    }

    private static String normalizeToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String collapsed = String.join(" ", token.trim().split("\\s+"));
        if (collapsed.indexOf(' ') >= 0) {
            int ascii = 0;
            int cjk = 0;
            for (int index = 0; index < collapsed.length(); ) {
                int codePoint = collapsed.codePointAt(index);
                index += Character.charCount(codePoint);
                if (Character.isWhitespace(codePoint)) {
                    continue;
                }
                if (codePoint < 128 && Character.isLetterOrDigit(codePoint)) {
                    ascii++;
                } else if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                    cjk++;
                }
            }
            if (cjk > 0 && cjk >= ascii) {
                return collapsed.replace(" ", "");
            }
        }
        return collapsed.toLowerCase(Locale.ROOT);
    }

    private static boolean isFunctionWord(int codePoint) {
        return switch (codePoint) {
            case '的', '了', '着', '呢', '吧', '吗', '呀', '啊', '嘛', '向', '所', '把', '被', '给', '和', '与',
                    '及', '又', '还', '都', '就', '再', '太', '很', '也', '让', '在', '是', '于', '我', '你', '他',
                    '她', '它', '们' -> true;
            default -> false;
        };
    }

    private record FieldContext(String indexField, String sourcePath, Analyzer analyzer) {
    }

    private static final class AssociateAccumulator {
        private static final Comparator<AssociateAccumulator> ORDER = Comparator
                .comparingDouble(AssociateAccumulator::score).reversed()
                .thenComparing(Comparator.comparingInt(AssociateAccumulator::docFreq).reversed())
                .thenComparing(AssociateAccumulator::text);

        private final String text;
        private final Set<String> fields = new LinkedHashSet<>();
        private int docFreq;
        private float score;

        private AssociateAccumulator(String text) {
            this.text = text;
        }

        private void add(float docWeight, String field) {
            docFreq++;
            score += docWeight;
            fields.add(field);
        }

        private float score() {
            return score + Math.max(0, fields.size() - 1) * 0.35f;
        }

        private int docFreq() {
            return docFreq;
        }

        private String text() {
            return text;
        }

        private SuggestionOption toSuggestion() {
            return new SuggestionOption(text, docFreq, score(), "associate");
        }
    }
}