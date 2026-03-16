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
import org.es.tok.text.SourceValueUtils;
import org.es.tok.text.TextNormalization;
import org.es.tok.text.TopicQualityHeuristics;
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
            seedTerms.add(TextNormalization.normalizeAnalyzedToken(text));
        }
        AssociateQueryProfile queryProfile = AssociateQueryProfile.from(seedTerms);

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
            collectCandidatesFromSource(candidates, fieldContexts, source, seedTerms, queryProfile, scoreDoc.score, rank);
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
            for (String seedTerm : analyze(fieldContext.analyzer(), fieldContext.indexField(), text)) {
                if (TopicQualityHeuristics.isUsefulAssociateSeedTerm(seedTerm)) {
                    seedTerms.add(seedTerm);
                }
            }
        }
        pruneAsciiSeedTerms(seedTerms, text);
        seedTerms.remove("");
        return TopicQualityHeuristics.filterAssociateSeedTerms(seedTerms);
    }

    private static void pruneAsciiSeedTerms(LinkedHashSet<String> seedTerms, String originalText) {
        if (seedTerms.isEmpty() || originalText == null || originalText.isBlank()) {
            return;
        }

        List<String> queryParts = extractAsciiQueryParts(originalText);
        if (queryParts.isEmpty()) {
            return;
        }

        LinkedHashSet<String> retained = new LinkedHashSet<>();
        for (String queryPart : queryParts) {
            String bestSeed = null;
            for (String seedTerm : seedTerms) {
                if (seedTerm == null || seedTerm.isBlank()) {
                    continue;
                }
                if (seedTerm.chars().allMatch(ch -> ch < 128 && Character.isLetterOrDigit(ch)) == false) {
                    continue;
                }
                if (queryPart.startsWith(seedTerm) == false) {
                    continue;
                }
                if (bestSeed == null || seedTerm.length() > bestSeed.length()) {
                    bestSeed = seedTerm;
                }
            }
            if (bestSeed != null) {
                retained.add(bestSeed);
            }
        }

        if (retained.isEmpty()) {
            return;
        }

        seedTerms.removeIf(seedTerm -> seedTerm != null
                && seedTerm.isBlank() == false
                && seedTerm.chars().allMatch(ch -> ch < 128 && Character.isLetterOrDigit(ch))
                && retained.contains(seedTerm) == false);
    }

    private void collectCandidatesFromSource(
            Map<String, AssociateAccumulator> candidates,
            List<FieldContext> fieldContexts,
            Source source,
            Set<String> seedTerms,
            AssociateQueryProfile queryProfile,
            float hitScore,
            int rank) throws IOException {
        float docWeight = (float) ((Math.log1p(Math.max(1.0f, hitScore)) + 1.0d) / (1.0d + (rank * 0.08d)));
        Set<String> seenInDoc = new LinkedHashSet<>();
        Map<String, Set<String>> tokenFields = new LinkedHashMap<>();
        for (FieldContext fieldContext : fieldContexts) {
            Object rawValue = source.extractValue(fieldContext.sourcePath(), null);
            for (String value : SourceValueUtils.flattenStringValues(rawValue)) {
                for (String token : analyze(fieldContext.analyzer(), fieldContext.indexField(), value)) {
                    tokenFields.computeIfAbsent(token, ignored -> new LinkedHashSet<>()).add(fieldContext.indexField());
                }
            }
        }
        for (String token : TopicQualityHeuristics.filterAssociateCandidateTerms(tokenFields.keySet())) {
            if (!isAcceptableAssociateCandidate(token, seedTerms, queryProfile)) {
                continue;
            }
            if (!seenInDoc.add(token)) {
                continue;
            }
            for (String field : tokenFields.getOrDefault(token, Set.of())) {
                candidates.computeIfAbsent(token, AssociateAccumulator::new)
                        .add(docWeight, field);
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
        if (field.endsWith(".assoc")) {
            return field.substring(0, field.length() - ".assoc".length());
        }
        return field;
    }

    private static List<String> extractAsciiQueryParts(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> parts = new ArrayList<>();
        for (String rawPart : text.trim().split("\\s+")) {
            String normalized = TextNormalization.normalizeAnalyzedToken(rawPart);
            if (!normalized.isBlank() && normalized.chars().allMatch(ch -> ch < 128 && Character.isLetterOrDigit(ch))) {
                parts.add(normalized);
            }
        }
        return parts;
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
                String normalized = TextNormalization.normalizeAnalyzedToken(termAttribute.toString());
                if (!normalized.isEmpty()) {
                    tokens.add(normalized);
                }
            }
            tokenStream.end();
        }
        return tokens;
    }

    private static boolean isAcceptableAssociateCandidate(String token, Set<String> seedTerms, AssociateQueryProfile queryProfile) {
        if (token == null || token.isBlank() || seedTerms.contains(token)) {
            return false;
        }
        if (token.indexOf(' ') >= 0) {
            return false;
        }
        int codePointLength = token.codePointCount(0, token.length());
        if (codePointLength == 1 && TopicQualityHeuristics.isFunctionWord(token.codePointAt(0))) {
            return false;
        }
        if (codePointLength > 24) {
            return false;
        }
        boolean hasMeaningfulLetter = token.chars().anyMatch(ch -> Character.isLetter(ch) || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN);
        if (!hasMeaningfulLetter) {
            return false;
        }
        if (token.chars().anyMatch(ch -> Character.isLetterOrDigit(ch) || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) == false) {
            return false;
        }
        return isUsefulAssociateCandidate(token, queryProfile);
    }

    private static boolean isUsefulAssociateCandidate(String token, AssociateQueryProfile queryProfile) {
        if (token == null || token.isBlank()) {
            return false;
        }

        int codePointLength = token.codePointCount(0, token.length());
        boolean asciiAlphaNum = token.chars().allMatch(ch -> ch < 128 && Character.isLetterOrDigit(ch));
        if (asciiAlphaNum) {
            boolean hasDigit = token.chars().anyMatch(Character::isDigit);
            if (codePointLength < (hasDigit ? 3 : 4)) {
                return false;
            }
            if (queryProfile.hasAsciiSeeds() && !queryProfile.isAnchoredAsciiCandidate(token)) {
                return false;
            }
            return true;
        }

        if (PinyinSupport.containsChinese(token)) {
            return codePointLength >= 2;
        }

        return codePointLength >= 2;
    }
    private record FieldContext(String indexField, String sourcePath, Analyzer analyzer) {
    }

    private static final class AssociateQueryProfile {
        private final List<String> asciiSeeds;

        private AssociateQueryProfile(List<String> asciiSeeds) {
            this.asciiSeeds = asciiSeeds;
        }

        private static AssociateQueryProfile from(Set<String> seedTerms) {
            List<String> asciiSeeds = seedTerms.stream()
                    .filter(seed -> seed != null && !seed.isBlank())
                    .filter(seed -> seed.chars().allMatch(ch -> ch < 128 && Character.isLetterOrDigit(ch)))
                    .filter(seed -> seed.codePointCount(0, seed.length()) >= 3)
                    .toList();
            return new AssociateQueryProfile(asciiSeeds);
        }

        private boolean hasAsciiSeeds() {
            return !asciiSeeds.isEmpty();
        }

        private boolean isAnchoredAsciiCandidate(String token) {
            boolean candidateHasDigit = token.chars().anyMatch(Character::isDigit);
            for (String seed : asciiSeeds) {
                if (!candidateHasDigit && token.length() < seed.length()) {
                    continue;
                }
                int sharedPrefix = sharedLeadingPrefixLength(seed, token);
                if (sharedPrefix >= Math.min(3, Math.min(seed.length(), token.length()))) {
                    return true;
                }
            }
            return false;
        }

        private static int sharedLeadingPrefixLength(String left, String right) {
            int limit = Math.min(left.length(), right.length());
            int shared = 0;
            while (shared < limit && left.charAt(shared) == right.charAt(shared)) {
                shared++;
            }
            return shared;
        }
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