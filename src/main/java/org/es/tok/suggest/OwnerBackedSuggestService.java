package org.es.tok.suggest;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.SourceFieldMetrics;
import org.elasticsearch.search.lookup.Source;
import org.elasticsearch.search.lookup.SourceProvider;
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

public class OwnerBackedSuggestService {

    private static final String OWNER_NAME_KEYWORD_FIELD = "owner.name.keyword";
    private static final String OWNER_NAME_SOURCE_PATH = "owner.name";
    private static final String OWNER_MID_SOURCE_PATH = "owner.mid";
    private static final String STAT_SCORE_SOURCE_PATH = "stat_score";
    private static final String STAT_VIEW_SOURCE_PATH = "stat.view";
    private static final String INSERT_AT_SOURCE_PATH = "insert_at";

    public boolean supports(Collection<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        for (String field : fields) {
            if (!OWNER_NAME_SOURCE_PATH.equals(sourcePath(field))) {
                return false;
            }
        }
        return true;
    }

    public List<SuggestionOption> suggestOwners(
            Engine.Searcher searcher,
            IndexService indexService,
            Collection<String> fields,
            String text,
            int size,
            String type) throws IOException {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<FieldContext> fieldContexts = resolveFieldContexts(indexService, fields);
        if (fieldContexts.isEmpty()) {
            return List.of();
        }

        Query query = buildAnalyzedOwnerQuery(fieldContexts, text);
        if (query == null) {
            return List.of();
        }

        int hitLimit = Math.max(512, size * 96);
        TopDocs topDocs = searcher.search(query, hitLimit);
        if (topDocs.scoreDocs.length == 0) {
            return List.of();
        }

        SourceProvider sourceProvider = SourceProvider.fromLookup(
                indexService.mapperService().mappingLookup(),
                null,
                SourceFieldMetrics.NOOP);
        Map<Long, OwnerAccumulator> owners = new LinkedHashMap<>();

        boolean exactPrefixMatched = collectExactPrefixOwnerMatches(
                searcher,
                sourceProvider,
                text,
                Math.max(256, size * 64),
                owners,
                type,
                6.0d);

        if (!exactPrefixMatched || shouldUseAnalyzedFallback(text, owners.size(), size)) {
            List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
            double fallbackWeight = exactPrefixMatched ? 0.18d : 1.0d;
            for (int rank = 0; rank < topDocs.scoreDocs.length; rank++) {
                ScoreDoc scoreDoc = topDocs.scoreDocs[rank];
                int leafIndex = ReaderUtil.subIndex(scoreDoc.doc, leaves);
                LeafReaderContext leaf = leaves.get(leafIndex);
                int leafDocId = scoreDoc.doc - leaf.docBase;
                Source source = sourceProvider.getSource(leaf, leafDocId);
                collectOwnerHit(owners, source, scoreDoc.score, rank, type, fallbackWeight);
            }
        }

        if (owners.isEmpty()) {
            return List.of();
        }

        return owners.values().stream()
                .sorted(OwnerAccumulator.ORDER)
                .limit(size)
                .map(OwnerAccumulator::toSuggestion)
                .toList();
    }

    public List<SuggestionOption> rerankOwnerCandidates(
            Engine.Searcher searcher,
            IndexService indexService,
            List<SuggestionOption> candidates,
            int size,
            String type) throws IOException {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        SourceProvider sourceProvider = SourceProvider.fromLookup(
                indexService.mapperService().mappingLookup(),
                null,
                SourceFieldMetrics.NOOP);
        Map<Long, OwnerAccumulator> owners = new LinkedHashMap<>();

        for (SuggestionOption candidate : candidates) {
            collectCandidateOwnerMatches(searcher, sourceProvider, candidate, owners, type);
        }

        if (owners.isEmpty()) {
            return List.of();
        }

        return owners.values().stream()
                .sorted(OwnerAccumulator.ORDER)
                .limit(size)
                .map(OwnerAccumulator::toSuggestion)
                .toList();
    }

    private void collectOwnerHit(
            Map<Long, OwnerAccumulator> owners,
            Source source,
            float hitScore,
            int rank,
            String type,
            double branchWeight) {
        Long mid = asLong(source.extractValue(OWNER_MID_SOURCE_PATH, null));
        if (mid == null) {
            return;
        }
        String ownerName = normalize(asString(source.extractValue(OWNER_NAME_SOURCE_PATH, null)));
        if (ownerName.isBlank()) {
            return;
        }

        double statScore = asDouble(source.extractValue(STAT_SCORE_SOURCE_PATH, null));
        long viewCount = asLong(source.extractValue(STAT_VIEW_SOURCE_PATH, null), 0L);
        long insertAt = asLong(source.extractValue(INSERT_AT_SOURCE_PATH, null), 0L);
        double ownerWeight = branchWeight * ownerDocWeight(statScore, viewCount, insertAt, hitScore, rank);

        owners.computeIfAbsent(mid, OwnerAccumulator::new)
                .add(ownerName, ownerWeight, type);
    }

    private void collectCandidateOwnerMatches(
            Engine.Searcher searcher,
            SourceProvider sourceProvider,
            SuggestionOption candidate,
            Map<Long, OwnerAccumulator> owners,
            String type) throws IOException {
        Query query = ownerKeywordPrefixQuery(candidate.text());
        TopDocs topDocs = searcher.search(query, 256);
        if (topDocs.scoreDocs.length == 0) {
            return;
        }

        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        for (int rank = 0; rank < topDocs.scoreDocs.length; rank++) {
            ScoreDoc scoreDoc = topDocs.scoreDocs[rank];
            int leafIndex = ReaderUtil.subIndex(scoreDoc.doc, leaves);
            LeafReaderContext leaf = leaves.get(leafIndex);
            int leafDocId = scoreDoc.doc - leaf.docBase;
            Source source = sourceProvider.getSource(leaf, leafDocId);
            collectOwnerHit(owners, source, scoreDoc.score, rank, type, 1.0d);
        }
    }

    private boolean collectExactPrefixOwnerMatches(
            Engine.Searcher searcher,
            SourceProvider sourceProvider,
            String text,
            int hitLimit,
            Map<Long, OwnerAccumulator> owners,
            String type,
            double branchWeight) throws IOException {
        List<String> variants = ownerNameVariants(text);
        if (variants.isEmpty()) {
            return false;
        }

        boolean matched = false;
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        for (String variant : variants) {
            TopDocs topDocs = searcher.search(new PrefixQuery(new Term(OWNER_NAME_KEYWORD_FIELD, variant)), hitLimit);
            if (topDocs.scoreDocs.length == 0) {
                continue;
            }
            matched = true;
            for (int rank = 0; rank < topDocs.scoreDocs.length; rank++) {
                ScoreDoc scoreDoc = topDocs.scoreDocs[rank];
                int leafIndex = ReaderUtil.subIndex(scoreDoc.doc, leaves);
                LeafReaderContext leaf = leaves.get(leafIndex);
                int leafDocId = scoreDoc.doc - leaf.docBase;
                Source source = sourceProvider.getSource(leaf, leafDocId);
                collectOwnerHit(owners, source, scoreDoc.score + 1.0f, rank, type, branchWeight);
            }
        }
        return matched;
    }

    private static Query ownerKeywordPrefixQuery(String candidateText) {
        List<String> variants = ownerNameVariants(candidateText);
        if (variants.size() == 1) {
            return new PrefixQuery(new Term(OWNER_NAME_KEYWORD_FIELD, variants.get(0)));
        }

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String variant : variants) {
            builder.add(new PrefixQuery(new Term(OWNER_NAME_KEYWORD_FIELD, variant)), BooleanClause.Occur.SHOULD);
        }
        return builder.build();
    }

    private static List<String> ownerNameVariants(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String collapsed = collapseWhitespace(text).trim();
        if (collapsed.isBlank()) {
            return List.of();
        }

        String tightened = tightenCjkAdjacentWhitespace(collapsed);
        if (tightened.equals(collapsed)) {
            return List.of(collapsed);
        }
        return List.of(collapsed, tightened);
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
            MappedFieldType fieldType = indexService.mapperService().fieldType(field);
            Analyzer analyzer = fieldType != null ? fieldType.getTextSearchInfo().searchAnalyzer() : Lucene.KEYWORD_ANALYZER;
            resolved.put(field, new FieldContext(field, analyzer));
        }
        return List.copyOf(resolved.values());
    }

    private LinkedHashSet<String> analyzeSeedTerms(List<FieldContext> fieldContexts, String text) throws IOException {
        LinkedHashSet<String> seedTerms = new LinkedHashSet<>();
        for (FieldContext fieldContext : fieldContexts) {
            for (String token : analyze(fieldContext.analyzer(), fieldContext.indexField(), text)) {
                if (!token.isBlank()) {
                    seedTerms.add(token);
                }
            }
        }
        return seedTerms;
    }

    private Query buildAnalyzedOwnerQuery(List<FieldContext> fieldContexts, String text) throws IOException {
        LinkedHashSet<String> seedTerms = analyzeSeedTerms(fieldContexts, text);
        if (seedTerms.isEmpty()) {
            seedTerms.add(normalize(text));
        }

        List<String> selectedTerms = selectQueryTerms(seedTerms, text);
        if (selectedTerms.isEmpty()) {
            return null;
        }

        boolean requireAll = PinyinSupport.containsChinese(text) || text.indexOf(' ') >= 0;
        BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();
        for (FieldContext fieldContext : fieldContexts) {
            BooleanQuery.Builder fieldQuery = new BooleanQuery.Builder();
            for (String seedTerm : selectedTerms) {
                if (!seedTerm.isBlank()) {
                    fieldQuery.add(
                            new PrefixQuery(new Term(fieldContext.indexField(), seedTerm)),
                            requireAll ? BooleanClause.Occur.MUST : BooleanClause.Occur.SHOULD);
                }
            }
            BooleanQuery builtFieldQuery = fieldQuery.build();
            if (!builtFieldQuery.clauses().isEmpty()) {
                queryBuilder.add(builtFieldQuery, BooleanClause.Occur.SHOULD);
            }
        }
        BooleanQuery query = queryBuilder.build();
        return query.clauses().isEmpty() ? null : query;
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
                String normalized = normalize(termAttribute.toString());
                if (!normalized.isBlank()) {
                    tokens.add(normalized);
                }
            }
            tokenStream.end();
        }
        return tokens;
    }

    private static double ownerDocWeight(double statScore, long viewCount, long insertAt, float hitScore, int rank) {
        double base = Math.max(1.0d, statScore);
        double popularity = Math.log1p(Math.max(0L, viewCount)) * 0.35d;
        double freshness = insertAt > 0 ? Math.log1p(Math.max(1L, insertAt / 86400L)) * 0.02d : 0.0d;
        double querySignal = (Math.log1p(Math.max(1.0f, hitScore)) + 1.0d) / (1.0d + (rank * 0.05d));
        return base + popularity + freshness + querySignal;
    }

    private static String sourcePath(String field) {
        if (field == null || field.isBlank()) {
            return "";
        }
        if (field.endsWith(".words")) {
            return field.substring(0, field.length() - ".words".length());
        }
        if (field.endsWith(".suggest")) {
            return field.substring(0, field.length() - ".suggest".length());
        }
        return field;
    }

    private record FieldContext(String indexField, Analyzer analyzer) {
    }

    private static boolean shouldUseAnalyzedFallback(String text, int exactOwnerCount, int requestedSize) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (!PinyinSupport.containsChinese(text)) {
            return true;
        }
        if (exactOwnerCount == 0) {
            return true;
        }
        return text.indexOf(' ') >= 0 && exactOwnerCount < requestedSize;
    }

    private static List<String> selectQueryTerms(LinkedHashSet<String> seedTerms, String text) {
        List<String> orderedTerms = new ArrayList<>(seedTerms);
        orderedTerms.sort(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo));

        boolean chineseQuery = PinyinSupport.containsChinese(text);
        List<String> selected = new ArrayList<>();
        for (String term : orderedTerms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            if (chineseQuery && PinyinSupport.isAsciiAlphaNumericQuery(term)) {
                continue;
            }
            boolean covered = false;
            for (String existing : selected) {
                if (existing.contains(term)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                selected.add(term);
            }
        }
        if (selected.isEmpty()) {
            return List.of(normalize(text));
        }
        return selected;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String collapseWhitespace(String text) {
        return String.join(" ", text.trim().split("\\s+"));
    }

    private static String tightenCjkAdjacentWhitespace(String text) {
        if (text == null || text.isBlank() || text.indexOf(' ') < 0) {
            return text == null ? "" : text;
        }

        int ascii = 0;
        int cjk = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
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
            return text.replace(" ", "");
        }
        return text;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long asLong(Object value) {
        return asLong(value, null);
    }

    private static Long asLong(Object value, Long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static double asDouble(Object value) {
        if (value == null) {
            return 0.0d;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return 0.0d;
        }
    }

    private static final class OwnerAccumulator {
        private static final Comparator<OwnerAccumulator> ORDER = Comparator
                .comparingDouble(OwnerAccumulator::score).reversed()
                .thenComparing(Comparator.comparingInt(OwnerAccumulator::docCount).reversed())
                .thenComparing(OwnerAccumulator::displayName);

        private final long mid;
        private final Map<String, Double> aliasScores = new HashMap<>();
        private double totalWeight;
        private double maxWeight;
        private int docCount;
        private String type = "prefix";

        private OwnerAccumulator(long mid) {
            this.mid = mid;
        }

        private void add(String ownerName, double ownerWeight, String type) {
            aliasScores.merge(ownerName, ownerWeight, Double::sum);
            totalWeight += ownerWeight;
            maxWeight = Math.max(maxWeight, ownerWeight);
            docCount++;
            this.type = type;
        }

        private double score() {
            double averageWeight = docCount > 0 ? totalWeight / docCount : 0.0d;
            return (maxWeight * 3.0d)
                    + (averageWeight * 1.25d)
                    + (Math.log1p(docCount) * 2.5d);
        }

        private int docCount() {
            return docCount;
        }

        private String displayName() {
            return aliasScores.entrySet().stream()
                    .max(Map.Entry.<String, Double>comparingByValue()
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElse(Long.toString(mid));
        }

        private SuggestionOption toSuggestion() {
            return new SuggestionOption(displayName(), docCount, (float) score(), type);
        }
    }
}