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
import java.time.Instant;
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
    private static final String TITLE_SOURCE_PATH = "title";
    private static final String TAGS_SOURCE_PATH = "tags";
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
        long nowEpochSeconds = Instant.now().getEpochSecond();

        boolean exactPrefixMatched = collectExactPrefixOwnerMatches(
                searcher,
                sourceProvider,
                nowEpochSeconds,
                text,
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
                collectOwnerHit(owners, scoreDoc.doc, source, text, nowEpochSeconds, scoreDoc.score, rank, true, type, fallbackWeight);
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
            String queryText,
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
        long nowEpochSeconds = Instant.now().getEpochSecond();

        for (SuggestionOption candidate : candidates) {
            collectCandidateOwnerMatches(searcher, sourceProvider, nowEpochSeconds, queryText, candidate, owners, type);
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
            int docId,
            Source source,
            String queryText,
            long nowEpochSeconds,
            float hitScore,
            int rank,
            boolean useQueryRankSignal,
            String type,
            double branchWeight) {
        Long mid = asLong(source.extractValue(OWNER_MID_SOURCE_PATH, null));
        if (mid == null) {
            return;
        }
        String ownerName = normalizeOwnerName(asString(source.extractValue(OWNER_NAME_SOURCE_PATH, null)));
        if (ownerName.isBlank()) {
            return;
        }

        float matchScore = PinyinSupport.prefixMatchScore(queryText, ownerName);
        boolean strictFullPinyinQuery = PinyinSupport.isPinyinLikeQuery(queryText)
                && PinyinSupport.isInitialsOnlyPinyinQuery(queryText) == false;
        if (strictFullPinyinQuery && PinyinSupport.fullPinyinPrefixMatch(queryText, ownerName) == false) {
            return;
        }
        if (("prefix".equals(type) || "auto".equals(type)) && matchScore <= 0.0f) {
            return;
        }

        double statScore = asDouble(source.extractValue(STAT_SCORE_SOURCE_PATH, null));
        long viewCount = asLong(source.extractValue(STAT_VIEW_SOURCE_PATH, null), 0L);
        long insertAt = asLong(source.extractValue(INSERT_AT_SOURCE_PATH, null), 0L);
        boolean asciiLiteralPrefix = containsAsciiLiteralPrefix(ownerName, PinyinSupport.normalizeInput(queryText));
        double topicalAffinity = ownerTopicAffinitySignal(
                queryText,
                asString(source.extractValue(TITLE_SOURCE_PATH, null)),
                source.extractValue(TAGS_SOURCE_PATH, null));
        if (shouldRejectStrictFullPinyinOwner(queryText, asciiLiteralPrefix, topicalAffinity)) {
            return;
        }
        OwnerDocSignals docSignals = ownerDocSignals(
            queryText,
            ownerName,
            matchScore,
            topicalAffinity,
            nowEpochSeconds,
            statScore,
            viewCount,
            insertAt,
            hitScore,
            rank,
            useQueryRankSignal);

        owners.computeIfAbsent(mid, OwnerAccumulator::new)
        .add(docId, ownerName, docSignals, branchWeight, type);
    }

    private static boolean shouldRejectStrictFullPinyinOwner(
            String queryText,
            boolean asciiLiteralPrefix,
            double topicalAffinity) {
        if (!PinyinSupport.isStrictFullPinyinQuery(queryText)) {
            return false;
        }
        if (asciiLiteralPrefix) {
            return false;
        }
        return topicalAffinity < 0.12d;
    }

    private void collectCandidateOwnerMatches(
            Engine.Searcher searcher,
            SourceProvider sourceProvider,
            long nowEpochSeconds,
            String queryText,
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
            collectOwnerHit(owners, scoreDoc.doc, source, queryText, nowEpochSeconds, scoreDoc.score, rank, false, type, 1.0d);
        }
    }

    private boolean collectExactPrefixOwnerMatches(
            Engine.Searcher searcher,
            SourceProvider sourceProvider,
            long nowEpochSeconds,
            String queryText,
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
                collectOwnerHit(owners, scoreDoc.doc, source, queryText, nowEpochSeconds, scoreDoc.score + 1.0f, rank, false, type, branchWeight);
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

    static OwnerDocSignals ownerDocSignals(
            String queryText,
            String ownerName,
            float matchScore,
            double topicalAffinity,
            long nowEpochSeconds,
            double statScore,
            long viewCount,
            long insertAt,
            float hitScore,
            int rank) {
        return ownerDocSignals(queryText, ownerName, matchScore, topicalAffinity, nowEpochSeconds, statScore, viewCount, insertAt, hitScore, rank, true);
    }

    static OwnerDocSignals ownerDocSignals(
            String queryText,
            String ownerName,
            float matchScore,
            double topicalAffinity,
            long nowEpochSeconds,
            double statScore,
            long viewCount,
            long insertAt,
            float hitScore,
            int rank,
            boolean useQueryRankSignal) {
        double normalizedStatScore = Math.max(0.0d, statScore);
        double quality = Math.log1p(normalizedStatScore * 2400.0d) * 4.0d;
        double influence = Math.log1p(Math.max(0L, viewCount)) * 2.8d;
        double ageDays = ageDays(nowEpochSeconds, insertAt);
        double recencyFactor = 1.0d / (1.0d + (ageDays / 14.0d));
        double freshness = recencyFactor * 3.0d;
        double matchSignal = Math.max(0.04d, matchScore);
        double disambiguationSignal = strictFullPinyinOwnerDisambiguation(queryText, ownerName);
        double topicSignal = Math.max(0.0d, topicalAffinity);
        double offTopicPenalty = strictFullPinyinOffTopicPenalty(queryText, ownerName, topicSignal);
        double querySignal = useQueryRankSignal
                ? ((Math.log1p(Math.max(1.0f, hitScore)) + 1.0d) * 0.9d) / (1.0d + (rank * 0.06d))
                : 0.0d;
        double rankingWeight = quality + influence + freshness + querySignal + (matchSignal * 7.0d) + (disambiguationSignal * 5.5d) + (topicSignal * 8.0d) - (offTopicPenalty * 28.0d);
        double activityWeight = (quality * 0.55d + influence * 0.65d + 1.0d) * recencyFactor;
        double representativeSignal = (quality * 1.2d) + (influence * 1.55d) + freshness + (disambiguationSignal * 2.2d) + (topicSignal * 3.4d) - (offTopicPenalty * 16.0d);
        return new OwnerDocSignals(rankingWeight, activityWeight, quality, influence, representativeSignal);
    }

    private static double strictFullPinyinOffTopicPenalty(String queryText, String ownerName, double topicalAffinity) {
        if (!PinyinSupport.isStrictFullPinyinQuery(queryText) || topicalAffinity >= 0.12d) {
            return 0.0d;
        }

        String normalizedQuery = PinyinSupport.normalizeInput(queryText);
        if (normalizedQuery.isBlank() || containsAsciiLiteralPrefix(ownerName, normalizedQuery)) {
            return 0.0d;
        }

        int chineseCount = chineseCodePointCount(ownerName);
        int asciiAlphaNumericCount = asciiAlphaNumericCount(ownerName);
        int separatorCount = separatorCount(ownerName);

        double penalty = 1.15d;
        if (chineseCount > 0 && asciiAlphaNumericCount == 0 && separatorCount == 0) {
            penalty += 0.35d;
        }
        if (chineseCount > 0 && (asciiAlphaNumericCount > 0 || separatorCount > 0)) {
            penalty += 0.75d;
        }
        return penalty;
    }

    private static double ownerTopicAffinitySignal(String queryText, String title, Object tagsValue) {
        if (!PinyinSupport.isPinyinLikeQuery(queryText) && !PinyinSupport.containsChinese(queryText)) {
            return 0.0d;
        }

        double best = 0.0d;
        for (String candidate : topicalTexts(title, tagsValue)) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            float score = PinyinSupport.prefixMatchScore(queryText, candidate);
            if (score > 0.0f) {
                best = Math.max(best, score);
                if (PinyinSupport.isStrictFullPinyinQuery(queryText) && PinyinSupport.fullPinyinPrefixMatch(queryText, candidate)) {
                    best = Math.max(best, score + 0.55d);
                }
            }
        }
        return best;
    }

    private static List<String> topicalTexts(String title, Object tagsValue) {
        List<String> texts = new ArrayList<>();
        if (title != null && !title.isBlank()) {
            texts.add(title);
        }
        if (tagsValue instanceof Collection<?> collection) {
            for (Object value : collection) {
                if (value != null) {
                    texts.add(value.toString());
                }
            }
        } else if (tagsValue instanceof String text && !text.isBlank()) {
            texts.add(text);
        }
        return texts;
    }

    private static double strictFullPinyinOwnerDisambiguation(String queryText, String ownerName) {
        if (!PinyinSupport.isStrictFullPinyinQuery(queryText) || ownerName == null || ownerName.isBlank()) {
            return 0.0d;
        }

        String normalizedQuery = PinyinSupport.normalizeInput(queryText);
        if (normalizedQuery.isBlank()) {
            return 0.0d;
        }

        double signal = 0.0d;
        if (containsAsciiLiteralPrefix(ownerName, normalizedQuery)) {
            signal += 1.9d;
        }

        int chineseCount = chineseCodePointCount(ownerName);
        int asciiAlphaNumericCount = asciiAlphaNumericCount(ownerName);
        int separatorCount = separatorCount(ownerName);
        boolean pureChineseSurface = chineseCount > 0 && asciiAlphaNumericCount == 0 && separatorCount == 0;
        boolean decoratedMixedSurface = chineseCount > 0 && (asciiAlphaNumericCount > 0 || separatorCount > 0);

        if (pureChineseSurface) {
            signal += 0.38d;
        }
        if (decoratedMixedSurface && !containsAsciiLiteralPrefix(ownerName, normalizedQuery)) {
            signal -= 0.72d;
            signal -= Math.min(0.28d, asciiAlphaNumericCount * 0.025d);
            signal -= Math.min(0.18d, separatorCount * 0.08d);
        }

        return signal;
    }

    private static boolean containsAsciiLiteralPrefix(String ownerName, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank() || ownerName == null || ownerName.isBlank()) {
            return false;
        }

        String lower = ownerName.toLowerCase(Locale.ROOT);
        StringBuilder segment = new StringBuilder();
        for (int index = 0; index < lower.length(); ) {
            int codePoint = lower.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint < 128 && Character.isLetterOrDigit(codePoint)) {
                segment.appendCodePoint(codePoint);
                continue;
            }
            if (segment.length() > 0) {
                if (segment.toString().startsWith(normalizedQuery)) {
                    return true;
                }
                segment.setLength(0);
            }
        }
        return segment.length() > 0 && segment.toString().startsWith(normalizedQuery);
    }

    private static int chineseCodePointCount(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                count++;
            }
        }
        return count;
    }

    private static int asciiAlphaNumericCount(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint < 128 && Character.isLetterOrDigit(codePoint)) {
                count++;
            }
        }
        return count;
    }

    private static int separatorCount(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (!Character.isWhitespace(codePoint)
                    && Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN
                    && !(codePoint < 128 && Character.isLetterOrDigit(codePoint))) {
                count++;
            }
        }
        return count;
    }

    private static double ageDays(long nowEpochSeconds, long insertAt) {
        if (insertAt <= 0L || nowEpochSeconds <= 0L) {
            return 3650.0d;
        }
        return Math.max(0.0d, (nowEpochSeconds - insertAt) / 86400.0d);
    }

    private static String sourcePath(String field) {
        if (field == null || field.isBlank()) {
            return "";
        }
        if (field.endsWith(".keyword")) {
            return field.substring(0, field.length() - ".keyword".length());
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

    private static String normalizeOwnerName(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return normalized;
        }
        return tightenCjkAdjacentWhitespace(collapseWhitespace(normalized));
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
        private final Map<Integer, OwnerDocContribution> contributionsByDoc = new HashMap<>();
        private String type = "prefix";

        private OwnerAccumulator(long mid) {
            this.mid = mid;
        }

        private void add(int docId, String ownerName, OwnerDocSignals docSignals, double branchWeight, String type) {
            OwnerDocContribution contribution = new OwnerDocContribution(
                    ownerName,
                    branchWeight * docSignals.rankingWeight(),
                    branchWeight * docSignals.activityWeight(),
                    docSignals.qualitySignal(),
                    docSignals.influenceSignal(),
                    docSignals.representativeSignal());
            contributionsByDoc.merge(docId, contribution, OwnerDocContribution::preferBetter);
            this.type = type;
        }

        private double score() {
            if (contributionsByDoc.isEmpty()) {
                return 0.0d;
            }

            List<OwnerDocContribution> contributions = contributionsByDoc.values().stream()
                    .sorted(Comparator
                            .comparingDouble(OwnerDocContribution::representativeSignal).reversed()
                            .thenComparing(Comparator.comparingDouble(OwnerDocContribution::rankingWeight).reversed()))
                    .toList();

            double bestRepresentativeSignal = contributions.get(0).representativeSignal();
            double secondRepresentativeSignal = contributions.size() > 1 ? contributions.get(1).representativeSignal() : 0.0d;
            double averageTopWeight = contributions.stream()
                    .limit(4)
                    .mapToDouble(OwnerDocContribution::rankingWeight)
                    .average()
                    .orElse(0.0d);
            double maxWeight = contributions.stream().mapToDouble(OwnerDocContribution::rankingWeight).max().orElse(0.0d);
            double maxQualitySignal = contributions.stream().mapToDouble(OwnerDocContribution::qualitySignal).max().orElse(0.0d);
            double maxInfluenceSignal = contributions.stream().mapToDouble(OwnerDocContribution::influenceSignal).max().orElse(0.0d);
            double activityWeight = contributions.stream().mapToDouble(OwnerDocContribution::activityWeight).sum();
            int docCount = contributions.size();
                return (bestRepresentativeSignal * 155.0d)
                    + (secondRepresentativeSignal * 20.0d)
                    + (maxWeight * 0.25d)
                    + (averageTopWeight * 0.05d)
                    + (maxQualitySignal * 12.0d)
                    + (maxInfluenceSignal * 18.0d)
                    + (Math.log1p(activityWeight) * 0.45d)
                    + (Math.log1p(docCount) * 0.003d);
        }

        private int docCount() {
            return contributionsByDoc.size();
        }

        private String displayName() {
            Map<String, Double> aliasScores = new HashMap<>();
            for (OwnerDocContribution contribution : contributionsByDoc.values()) {
                aliasScores.merge(contribution.ownerName(), contribution.rankingWeight(), Double::sum);
            }
            return aliasScores.entrySet().stream()
                    .max(Map.Entry.<String, Double>comparingByValue()
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElse(Long.toString(mid));
        }

        private SuggestionOption toSuggestion() {
            return new SuggestionOption(displayName(), docCount(), (float) score(), type);
        }
    }

    record OwnerDocSignals(
            double rankingWeight,
            double activityWeight,
            double qualitySignal,
            double influenceSignal,
            double representativeSignal) {
    }

    record OwnerDocContribution(
            String ownerName,
            double rankingWeight,
            double activityWeight,
            double qualitySignal,
            double influenceSignal,
            double representativeSignal) {

        private OwnerDocContribution preferBetter(OwnerDocContribution other) {
            if (other == null) {
                return this;
            }
            if (other.rankingWeight() > rankingWeight()) {
                return other;
            }
            if (other.rankingWeight() < rankingWeight()) {
                return this;
            }
            if (other.representativeSignal() > representativeSignal()) {
                return other;
            }
            if (other.representativeSignal() < representativeSignal()) {
                return this;
            }
            return other.ownerName().compareTo(ownerName()) >= 0 ? other : this;
        }
    }
}