package org.es.tok.relations;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.SourceFieldMetrics;
import org.elasticsearch.search.lookup.Source;
import org.elasticsearch.search.lookup.SourceProvider;
import org.es.tok.action.EsTokEntityRelationRequest;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SourceBackedEntityRelationsService {
    private static final String BVID_SOURCE_PATH = "bvid";
    private static final String TITLE_SOURCE_PATH = "title";
    private static final String OWNER_NAME_SOURCE_PATH = "owner.name";
    private static final String OWNER_MID_SOURCE_PATH = "owner.mid";
    private static final String STAT_SCORE_SOURCE_PATH = "stat_score";
    private static final String STAT_VIEW_SOURCE_PATH = "stat.view";
    private static final String INSERT_AT_SOURCE_PATH = "insert_at";

    public RelationResult searchRelations(
            Engine.Searcher searcher,
            IndexService indexService,
            String relation,
            List<String> bvids,
            List<Long> mids,
            int size,
            int scanLimit) throws IOException {
        List<FieldContext> topicFields = resolveTopicFields(indexService);
        AnalysisCache analysisCache = new AnalysisCache();
        SeedContext seedContext = loadSeedContext(searcher, indexService, relation, bvids, mids, scanLimit, topicFields, analysisCache);
        if (seedContext.isEmpty()) {
            return RelationResult.empty();
        }

        seedContext.prepareQueryTerms(searcher, topicFields);
        if (!seedContext.hasQueryTerms()) {
            return RelationResult.empty();
        }

        Query candidateQuery = buildCandidateQuery(relation, seedContext, topicFields);
        if (candidateQuery == null) {
            return RelationResult.empty();
        }

        TopDocs topDocs = searcher.search(candidateQuery, candidateDocLimit(size, scanLimit));
        if (topDocs.scoreDocs.length == 0) {
            return RelationResult.empty();
        }

        SourceProvider sourceProvider = SourceProvider.fromLookup(
                indexService.mapperService().mappingLookup(),
                null,
                SourceFieldMetrics.NOOP);
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        long nowEpochSeconds = Instant.now().getEpochSecond();

        RelationResult result = collectRelationResults(
                relation,
                seedContext,
                topicFields,
                topDocs,
                false,
                size,
                sourceProvider,
                leaves,
                nowEpochSeconds,
                analysisCache);
        if (!result.isEmpty() || !supportsRelaxedFallback(relation)) {
            return result;
        }

        return collectRelationResults(
                relation,
                seedContext,
                topicFields,
                topDocs,
                true,
                size,
                sourceProvider,
                leaves,
                nowEpochSeconds,
                analysisCache);
    }

    private RelationResult collectRelationResults(
            String relation,
            SeedContext seedContext,
            List<FieldContext> topicFields,
            TopDocs topDocs,
            boolean relaxedMode,
            int size,
            SourceProvider sourceProvider,
            List<LeafReaderContext> leaves,
            long nowEpochSeconds,
            AnalysisCache analysisCache) throws IOException {
        Map<String, VideoAccumulator> videos = new LinkedHashMap<>();
        Map<Long, OwnerAccumulator> owners = new LinkedHashMap<>();
        for (int rank = 0; rank < topDocs.scoreDocs.length; rank++) {
            ScoreDoc scoreDoc = topDocs.scoreDocs[rank];
            int leafIndex = ReaderUtil.subIndex(scoreDoc.doc, leaves);
            LeafReaderContext leaf = leaves.get(leafIndex);
            int leafDocId = scoreDoc.doc - leaf.docBase;
            Source source = sourceProvider.getSource(leaf, leafDocId);

            String bvid = normalizeIdentifier(source.extractValue(BVID_SOURCE_PATH, null));
            long ownerMid = asLong(source.extractValue(OWNER_MID_SOURCE_PATH, null), -1L);
            String ownerName = normalizeDisplay(source.extractValue(OWNER_NAME_SOURCE_PATH, null));
            String title = normalizeDisplay(source.extractValue(TITLE_SOURCE_PATH, null));
            if (bvid.isBlank() || ownerMid < 0L || title.isBlank()) {
                continue;
            }

            if (EsTokEntityRelationRequest.RELATED_OWNERS_BY_OWNERS.equals(relation)
                    && seedContext.containsOwnerMid(ownerMid)) {
                continue;
            }

            DocSignals signals = computeSignals(relation, seedContext, topicFields, source, scoreDoc.score, rank, nowEpochSeconds, relaxedMode, analysisCache);
            if (signals.score() <= 0.0d) {
                continue;
            }

            if (EsTokEntityRelationRequest.RELATED_VIDEOS_BY_VIDEOS.equals(relation)
                    || EsTokEntityRelationRequest.RELATED_VIDEOS_BY_OWNERS.equals(relation)) {
                if (seedContext.containsBvid(bvid)) {
                    continue;
                }
                videos.computeIfAbsent(bvid, ignored -> new VideoAccumulator(bvid))
                        .add(title, ownerMid, ownerName, signals.score());
            } else {
                owners.computeIfAbsent(ownerMid, OwnerAccumulator::new)
                        .add(ownerName, signals.score());
            }
        }

        List<RelatedVideoResult> videoResults = selectVideoResults(relation, videos, size);
        List<RelatedOwnerResult> ownerResults = selectOwnerResults(relation, owners, seedContext, size, relaxedMode);
        return new RelationResult(videoResults, ownerResults);
    }

    private SeedContext loadSeedContext(
            Engine.Searcher searcher,
            IndexService indexService,
            String relation,
            List<String> bvids,
            List<Long> mids,
            int scanLimit,
            List<FieldContext> topicFields,
            AnalysisCache analysisCache) throws IOException {
        Query seedQuery = buildSeedQuery(relation, bvids, mids);
        if (seedQuery == null) {
            return SeedContext.empty();
        }

        TopDocs topDocs = searcher.search(seedQuery, Math.max(scanLimit, 64));
        if (topDocs.scoreDocs.length == 0) {
            return SeedContext.empty();
        }

        SourceProvider sourceProvider = SourceProvider.fromLookup(
                indexService.mapperService().mappingLookup(),
                null,
                SourceFieldMetrics.NOOP);
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        SeedContext seedContext = new SeedContext();
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            int leafIndex = ReaderUtil.subIndex(scoreDoc.doc, leaves);
            LeafReaderContext leaf = leaves.get(leafIndex);
            int leafDocId = scoreDoc.doc - leaf.docBase;
            Source source = sourceProvider.getSource(leaf, leafDocId);
            seedContext.addBvid(normalizeIdentifier(source.extractValue(BVID_SOURCE_PATH, null)));
            seedContext.addOwnerMid(asLong(source.extractValue(OWNER_MID_SOURCE_PATH, null), -1L));
            seedContext.addInsertAt(asLong(source.extractValue(INSERT_AT_SOURCE_PATH, null), 0L));
            seedContext.addTokens(extractTopicTokenWeights(topicFields, source, analysisCache));
        }
        return seedContext;
    }

    private Query buildSeedQuery(String relation, List<String> bvids, List<Long> mids) {
        if (EsTokEntityRelationRequest.RELATED_VIDEOS_BY_VIDEOS.equals(relation)
                || EsTokEntityRelationRequest.RELATED_OWNERS_BY_VIDEOS.equals(relation)) {
            if (bvids == null || bvids.isEmpty()) {
                return null;
            }
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            for (String bvid : bvids) {
                if (bvid != null && !bvid.isBlank()) {
                    builder.add(new TermQuery(new Term("bvid.keyword", bvid)), BooleanClause.Occur.SHOULD);
                }
            }
            BooleanQuery query = builder.build();
            return query.clauses().isEmpty() ? null : query;
        }
        if (mids == null || mids.isEmpty()) {
            return null;
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (Long mid : mids) {
            if (mid != null && mid > 0L) {
                builder.add(LongPoint.newExactQuery("owner.mid", mid), BooleanClause.Occur.SHOULD);
            }
        }
        BooleanQuery query = builder.build();
        return query.clauses().isEmpty() ? null : query;
    }

    private Query buildCandidateQuery(String relation, SeedContext seedContext, List<FieldContext> topicFields) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (FieldContext fieldContext : topicFields) {
            for (TokenWeight tokenWeight : seedContext.queryTerms()) {
                float exactBoost = (float) (fieldContext.queryBoost() * tokenWeight.exactBoost());
                builder.add(new BoostQuery(new TermQuery(new Term(fieldContext.indexField(), tokenWeight.token())), exactBoost), BooleanClause.Occur.SHOULD);
                if (tokenWeight.allowPrefix()) {
                    float prefixBoost = (float) (fieldContext.prefixBoost() * tokenWeight.prefixBoost());
                    builder.add(new BoostQuery(new PrefixQuery(new Term(fieldContext.indexField(), tokenWeight.token())), prefixBoost), BooleanClause.Occur.SHOULD);
                }
            }
        }
        if (!EsTokEntityRelationRequest.RELATED_OWNERS_BY_OWNERS.equals(relation)) {
            float ownerBoost = ownerCandidateBoost(relation);
            for (Long mid : seedContext.seedOwnerMids()) {
                if (mid != null && mid > 0L) {
                    builder.add(new BoostQuery(LongPoint.newExactQuery("owner.mid", mid), ownerBoost), BooleanClause.Occur.SHOULD);
                }
            }
        }

        BooleanQuery query = builder.build();
        return query.clauses().isEmpty() ? null : query;
    }

    private DocSignals computeSignals(
            String relation,
            SeedContext seedContext,
            List<FieldContext> topicFields,
            Source source,
            float hitScore,
            int rank,
            long nowEpochSeconds,
            boolean relaxedMode,
            AnalysisCache analysisCache) throws IOException {
        Set<String> candidateTokens = extractTopicTokens(topicFields, source, analysisCache);
        double overlapWeight = 0.0d;
        int overlapCount = 0;
        int strongOverlapCount = 0;
        for (String token : candidateTokens) {
            TokenWeight tokenWeight = seedContext.queryWeight(token);
            if (tokenWeight == null) {
                continue;
            }
            overlapWeight += tokenWeight.signalWeight();
            overlapCount++;
            if (tokenWeight.strongSignal()) {
                strongOverlapCount++;
            }
        }

        long ownerMid = asLong(source.extractValue(OWNER_MID_SOURCE_PATH, null), -1L);
        boolean sameOwner = seedContext.containsOwnerMid(ownerMid);
        double coverage = seedContext.totalQueryWeight() <= 0.0d ? 0.0d : overlapWeight / seedContext.totalQueryWeight();
        if (overlapWeight <= 0.0d && !sameOwner) {
            return DocSignals.zero();
        }
        if (coverage < minimumCoverage(relation, relaxedMode) && strongOverlapCount == 0 && !sameOwner) {
            return DocSignals.zero();
        }
        if (requiresStrongMatch(relation, relaxedMode) && strongOverlapCount == 0 && !sameOwner) {
            return DocSignals.zero();
        }
        if (!sameOwner && overlapCount < minimumOverlapCount(relation, relaxedMode)) {
            return DocSignals.zero();
        }
        if (!sameOwner && overlapCount < (relaxedMode ? 1 : 2) && coverage < (relaxedMode ? 0.14d : 0.22d)) {
            return DocSignals.zero();
        }

        double ownerBoost = sameOwner ? ownerScoreBoost(relation) : 0.0d;
        double hitWeight = Math.log1p(Math.max(0.0d, hitScore)) / (1.0d + (rank * 0.045d));
        double recency = recencyScore(nowEpochSeconds, seedContext.seedInsertAt, asLong(source.extractValue(INSERT_AT_SOURCE_PATH, null), 0L));
        double quality = Math.log1p(Math.max(0.0d, asDouble(source.extractValue(STAT_SCORE_SOURCE_PATH, null))) * 2400.0d);
        double influence = Math.log1p(Math.max(0L, asLong(source.extractValue(STAT_VIEW_SOURCE_PATH, null), 0L)));

        double score = (overlapWeight * 12.0d)
                + (coverage * 78.0d)
                + (strongOverlapCount * 14.0d)
                + (overlapCount * 2.5d)
                + ownerBoost
                + (hitWeight * 2.4d)
                + (recency * 12.0d)
                + (quality * 8.0d)
                + (influence * 3.2d);
        if (!sameOwner && coverage < (relaxedMode ? 0.12d : 0.18d)) {
            score *= relaxedMode ? 0.78d : 0.62d;
        }
        return new DocSignals(score);
    }

    private double recencyScore(long nowEpochSeconds, List<Long> seedInsertAt, long candidateInsertAt) {
        if (candidateInsertAt <= 0L || seedInsertAt.isEmpty()) {
            return 0.0d;
        }
        long minDiff = Long.MAX_VALUE;
        for (Long seedTime : seedInsertAt) {
            if (seedTime == null || seedTime <= 0L) {
                continue;
            }
            minDiff = Math.min(minDiff, Math.abs(seedTime - candidateInsertAt));
        }
        if (minDiff == Long.MAX_VALUE) {
            return 0.0d;
        }
        double agePenalty = Math.max(0.0d, (nowEpochSeconds - candidateInsertAt) / 86400.0d);
        return (1.0d / (1.0d + (minDiff / 86400.0d))) * (1.0d / (1.0d + (agePenalty / 30.0d)));
    }

    private List<FieldContext> resolveTopicFields(IndexService indexService) {
        List<FieldContext> resolved = new ArrayList<>();
        addFieldContext(indexService, resolved, "title.words", 5.0d, 1.8d, 0.7d);
        addFieldContext(indexService, resolved, "tags.words", 4.0d, 1.55d, 0.6d);
        addFieldContext(indexService, resolved, "desc.words", 1.8d, 0.9d, 0.28d);
        return resolved;
    }

    private void addFieldContext(
            IndexService indexService,
            List<FieldContext> resolved,
            String field,
            double seedWeight,
            double queryBoost,
            double prefixBoost) {
        MappedFieldType fieldType = indexService.mapperService().fieldType(field);
        if (fieldType == null) {
            return;
        }
        resolved.add(new FieldContext(field, sourcePath(field), fieldType.getTextSearchInfo().searchAnalyzer(), seedWeight, queryBoost, prefixBoost));
    }

    private Map<String, Double> extractTopicTokenWeights(List<FieldContext> fieldContexts, Source source, AnalysisCache analysisCache) throws IOException {
        LinkedHashMap<String, Double> tokens = new LinkedHashMap<>();
        for (FieldContext fieldContext : fieldContexts) {
            Object rawValue = source.extractValue(fieldContext.sourcePath(), null);
            for (String value : flattenSourceValues(rawValue)) {
                for (String token : analysisCache.analyze(fieldContext, value)) {
                    if (!token.isBlank()) {
                        tokens.merge(token, fieldContext.seedWeight(), Double::sum);
                    }
                }
            }
        }
        return tokens;
    }

    private Set<String> extractTopicTokens(List<FieldContext> fieldContexts, Source source, AnalysisCache analysisCache) throws IOException {
        return extractTopicTokenWeights(fieldContexts, source, analysisCache).keySet();
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
                String token = normalizeString(termAttribute.toString());
                if (!token.isBlank()) {
                    tokens.add(token);
                }
            }
            tokenStream.end();
        }
        return tokens;
    }

    private static float ownerCandidateBoost(String relation) {
        if (EsTokEntityRelationRequest.RELATED_VIDEOS_BY_OWNERS.equals(relation)) {
            return 2.8f;
        }
        if (EsTokEntityRelationRequest.RELATED_OWNERS_BY_VIDEOS.equals(relation)) {
            return 1.8f;
        }
        return 1.5f;
    }

    private static double ownerScoreBoost(String relation) {
        if (EsTokEntityRelationRequest.RELATED_VIDEOS_BY_OWNERS.equals(relation)) {
            return 34.0d;
        }
        if (EsTokEntityRelationRequest.RELATED_OWNERS_BY_VIDEOS.equals(relation)) {
            return 22.0d;
        }
        if (EsTokEntityRelationRequest.RELATED_VIDEOS_BY_VIDEOS.equals(relation)) {
            return 16.0d;
        }
        return 0.0d;
    }

    private static int candidateDocLimit(int size, int scanLimit) {
        int requested = Math.max(scanLimit, size * 12);
        return Math.min(Math.max(requested, size * 8), 128);
    }

    private static double minimumCoverage(String relation, boolean relaxedMode) {
        if (EsTokEntityRelationRequest.RELATED_OWNERS_BY_OWNERS.equals(relation)) {
            return relaxedMode ? 0.12d : 0.20d;
        }
        if (EsTokEntityRelationRequest.RELATED_VIDEOS_BY_VIDEOS.equals(relation)) {
            return relaxedMode ? 0.08d : 0.12d;
        }
        return relaxedMode ? 0.05d : 0.08d;
    }

    private static int minimumOverlapCount(String relation, boolean relaxedMode) {
        if (EsTokEntityRelationRequest.RELATED_OWNERS_BY_OWNERS.equals(relation)) {
            return relaxedMode ? 1 : 2;
        }
        if (EsTokEntityRelationRequest.RELATED_VIDEOS_BY_VIDEOS.equals(relation)) {
            return relaxedMode ? 1 : 2;
        }
        return 1;
    }

    private static boolean acceptOwnerCandidate(String relation, OwnerAccumulator accumulator, boolean relaxedMode) {
        if (!EsTokEntityRelationRequest.RELATED_OWNERS_BY_OWNERS.equals(relation)) {
            return true;
        }
        if (relaxedMode) {
            return accumulator.docFreq() >= 1 && accumulator.score() >= 240.0d;
        }
        return accumulator.docFreq() >= 2 || accumulator.score() >= 320.0d;
    }

    private static boolean requiresStrongMatch(String relation, boolean relaxedMode) {
        if (relaxedMode) {
            return false;
        }
        return EsTokEntityRelationRequest.RELATED_VIDEOS_BY_VIDEOS.equals(relation)
                || EsTokEntityRelationRequest.RELATED_OWNERS_BY_OWNERS.equals(relation);
    }

    private static boolean supportsRelaxedFallback(String relation) {
        return EsTokEntityRelationRequest.RELATED_VIDEOS_BY_VIDEOS.equals(relation)
                || EsTokEntityRelationRequest.RELATED_VIDEOS_BY_OWNERS.equals(relation)
                || EsTokEntityRelationRequest.RELATED_OWNERS_BY_OWNERS.equals(relation);
    }

    private static List<RelatedVideoResult> selectVideoResults(
            String relation,
            Map<String, VideoAccumulator> videos,
            int size) {
        List<VideoAccumulator> ordered = videos.values().stream()
                .sorted(VideoAccumulator.ORDER)
                .toList();
        if (!EsTokEntityRelationRequest.RELATED_VIDEOS_BY_VIDEOS.equals(relation)
                && !EsTokEntityRelationRequest.RELATED_VIDEOS_BY_OWNERS.equals(relation)) {
            return ordered.stream().limit(size).map(VideoAccumulator::toResult).toList();
        }

        List<RelatedVideoResult> selected = new ArrayList<>();
        Map<Long, Integer> ownerCounts = new HashMap<>();
        List<VideoAccumulator> remaining = new ArrayList<>(ordered);
        while (!remaining.isEmpty() && selected.size() < size) {
            VideoAccumulator best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (VideoAccumulator candidate : remaining) {
                int ownerCount = ownerCounts.getOrDefault(candidate.ownerMid(), 0);
                double adjustedScore = candidate.score() / (1.0d + (ownerCount * 0.45d));
                if (adjustedScore > bestScore) {
                    bestScore = adjustedScore;
                    best = candidate;
                }
            }
            if (best == null) {
                break;
            }
            remaining.remove(best);
            selected.add(best.toResult());
            ownerCounts.merge(best.ownerMid(), 1, Integer::sum);
        }
        return selected;
    }

    private static List<RelatedOwnerResult> selectOwnerResults(
            String relation,
            Map<Long, OwnerAccumulator> owners,
            SeedContext seedContext,
            int size,
            boolean relaxedMode) {
        List<OwnerAccumulator> ordered = owners.values().stream()
                .filter(accumulator -> acceptOwnerCandidate(relation, accumulator, relaxedMode))
                .sorted(OwnerAccumulator.ORDER)
                .toList();
        if (!EsTokEntityRelationRequest.RELATED_OWNERS_BY_VIDEOS.equals(relation)) {
            return ordered.stream().limit(size).map(OwnerAccumulator::toResult).toList();
        }

        List<RelatedOwnerResult> results = new ArrayList<>();
        List<OwnerAccumulator> sameOwner = new ArrayList<>();
        for (OwnerAccumulator accumulator : ordered) {
            if (seedContext.containsOwnerMid(accumulator.mid())) {
                sameOwner.add(accumulator);
                continue;
            }
            if (results.size() < size) {
                results.add(accumulator.toResult());
            }
        }
        if (results.isEmpty()) {
            return sameOwner.stream().limit(size).map(OwnerAccumulator::toResult).toList();
        }
        return results;
    }

    private static boolean isCandidateQueryToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (token.length() < 2) {
            return false;
        }
        boolean hasLetterOrIdeograph = false;
        boolean allDigits = true;
        for (int index = 0; index < token.length(); index++) {
            char current = token.charAt(index);
            if (Character.isLetter(current) || Character.UnicodeScript.of(current) == Character.UnicodeScript.HAN) {
                hasLetterOrIdeograph = true;
            }
            if (!Character.isDigit(current)) {
                allDigits = false;
            }
        }
        return hasLetterOrIdeograph && !allDigits;
    }

    private static boolean isStrongSignalToken(String token) {
        if (!isCandidateQueryToken(token)) {
            return false;
        }
        int hanCount = 0;
        int asciiLetterCount = 0;
        for (int index = 0; index < token.length(); index++) {
            char current = token.charAt(index);
            if (Character.UnicodeScript.of(current) == Character.UnicodeScript.HAN) {
                hanCount++;
            }
            if (current < 128 && Character.isLetter(current)) {
                asciiLetterCount++;
            }
        }
        return hanCount >= 2 || asciiLetterCount >= 4 || token.length() >= 4;
    }

    private static double tokenLengthBoost(String token) {
        int hanCount = 0;
        int otherCount = 0;
        for (int index = 0; index < token.length(); index++) {
            if (Character.UnicodeScript.of(token.charAt(index)) == Character.UnicodeScript.HAN) {
                hanCount++;
            } else {
                otherCount++;
            }
        }
        return 1.0d + (hanCount * 0.12d) + (otherCount * 0.04d);
    }

    private static List<TokenWeight> selectQueryTerms(Engine.Searcher searcher, List<FieldContext> fieldContexts, Map<String, Double> seedTerms)
            throws IOException {
        int docCount = Math.max(1, searcher.getIndexReader().numDocs());
        List<TokenWeight> weighted = new ArrayList<>();
        for (Map.Entry<String, Double> entry : seedTerms.entrySet()) {
            String token = entry.getKey();
            if (!isCandidateQueryToken(token)) {
                continue;
            }
            long docFrequency = 0L;
            for (FieldContext fieldContext : fieldContexts) {
                docFrequency += searcher.getIndexReader().docFreq(new Term(fieldContext.indexField(), token));
            }
            double rarityBoost = 1.0d + (Math.log1p((double) docCount / (1.0d + docFrequency)) / 4.0d);
            double signalWeight = Math.sqrt(entry.getValue()) * tokenLengthBoost(token) * rarityBoost;
            boolean strongSignal = isStrongSignalToken(token);
            weighted.add(new TokenWeight(
                    token,
                    Math.min(5.2d, signalWeight * 0.55d),
                    Math.min(1.6d, signalWeight * 0.16d),
                    signalWeight,
                    strongSignal,
                    strongSignal || token.length() >= 3));
        }
        weighted.sort(Comparator
                .comparingDouble(TokenWeight::signalWeight).reversed()
                .thenComparing(tokenWeight -> tokenWeight.token().length(), Comparator.reverseOrder())
                .thenComparing(TokenWeight::token));

        List<TokenWeight> selected = new ArrayList<>();
        for (TokenWeight tokenWeight : weighted) {
            boolean covered = false;
            for (TokenWeight existing : selected) {
                if (existing.token().contains(tokenWeight.token())
                        && existing.signalWeight() >= tokenWeight.signalWeight() * 1.1d) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                selected.add(tokenWeight);
            }
            if (selected.size() >= 12) {
                break;
            }
        }
        return selected;
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

    private static String sourcePath(String field) {
        if (field.endsWith(".words")) {
            return field.substring(0, field.length() - ".words".length());
        }
        return field;
    }

    private static String normalizeString(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeDisplay(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim();
    }

    private static String normalizeIdentifier(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim();
    }

    private static long asLong(Object value, long defaultValue) {
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

    private record FieldContext(
            String indexField,
            String sourcePath,
            Analyzer analyzer,
            double seedWeight,
            double queryBoost,
            double prefixBoost) {
    }

    private record DocSignals(double score) {
        private static DocSignals zero() {
            return new DocSignals(0.0d);
        }
    }

    private record TokenWeight(
            String token,
            double exactBoost,
            double prefixBoost,
            double signalWeight,
            boolean strongSignal,
            boolean allowPrefix) {
    }

    public record RelatedVideoResult(String bvid, String title, long ownerMid, String ownerName, int docFreq, float score) {
    }

    public record RelatedOwnerResult(long mid, String name, int docFreq, float score) {
    }

    public record RelationResult(List<RelatedVideoResult> videos, List<RelatedOwnerResult> owners) {
        private static RelationResult empty() {
            return new RelationResult(List.of(), List.of());
        }

        private boolean isEmpty() {
            return videos.isEmpty() && owners.isEmpty();
        }
    }

    private static final class AnalysisCache {
        private final Map<String, List<String>> analyzedTexts = new HashMap<>();

        private List<String> analyze(FieldContext fieldContext, String text) throws IOException {
            if (text == null || text.isBlank()) {
                return List.of();
            }
            String cacheKey = fieldContext.indexField() + "\u0000" + text;
            List<String> cached = analyzedTexts.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            List<String> analyzed = List.copyOf(SourceBackedEntityRelationsService.analyze(fieldContext.analyzer(), fieldContext.indexField(), text));
            analyzedTexts.put(cacheKey, analyzed);
            return analyzed;
        }
    }

    private static final class SeedContext {
        private final LinkedHashSet<String> seedBvids = new LinkedHashSet<>();
        private final LinkedHashSet<String> seedBvidKeys = new LinkedHashSet<>();
        private final LinkedHashSet<Long> seedOwnerMids = new LinkedHashSet<>();
        private final LinkedHashMap<String, Double> tokenSeedWeights = new LinkedHashMap<>();
        private final List<Long> seedInsertAt = new ArrayList<>();
        private List<TokenWeight> queryTerms = List.of();
        private Map<String, TokenWeight> queryTermsByToken = Map.of();
        private double totalQueryWeight;

        private void addBvid(String bvid) {
            if (!bvid.isBlank()) {
                seedBvids.add(bvid);
                seedBvidKeys.add(normalizeString(bvid));
            }
        }

        private void addOwnerMid(long mid) {
            if (mid > 0L) {
                seedOwnerMids.add(mid);
            }
        }

        private void addInsertAt(long insertAt) {
            if (insertAt > 0L) {
                seedInsertAt.add(insertAt);
            }
        }

        private void addTokens(Map<String, Double> tokens) {
            for (Map.Entry<String, Double> entry : tokens.entrySet()) {
                tokenSeedWeights.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }

        private void prepareQueryTerms(Engine.Searcher searcher, List<FieldContext> fieldContexts) throws IOException {
            queryTerms = Collections.unmodifiableList(selectQueryTerms(searcher, fieldContexts, tokenSeedWeights));
            LinkedHashMap<String, TokenWeight> tokenWeights = new LinkedHashMap<>();
            double total = 0.0d;
            for (TokenWeight tokenWeight : queryTerms) {
                tokenWeights.put(tokenWeight.token(), tokenWeight);
                total += tokenWeight.signalWeight();
            }
            queryTermsByToken = Map.copyOf(tokenWeights);
            totalQueryWeight = total;
        }

        private boolean hasQueryTerms() {
            return !queryTerms.isEmpty() && totalQueryWeight > 0.0d;
        }

        private List<TokenWeight> queryTerms() {
            return queryTerms;
        }

        private TokenWeight queryWeight(String token) {
            return queryTermsByToken.get(token);
        }

        private double totalQueryWeight() {
            return totalQueryWeight;
        }

        private boolean containsBvid(String bvid) {
            return seedBvidKeys.contains(normalizeString(bvid));
        }

        private boolean containsOwnerMid(long mid) {
            return seedOwnerMids.contains(mid);
        }

        private Collection<Long> seedOwnerMids() {
            return seedOwnerMids;
        }

        private boolean isEmpty() {
            return seedBvids.isEmpty() && seedOwnerMids.isEmpty();
        }

        private static SeedContext empty() {
            return new SeedContext();
        }
    }

    private static final class VideoAccumulator {
        private static final Comparator<VideoAccumulator> ORDER = Comparator
                .comparingDouble(VideoAccumulator::score).reversed()
                .thenComparing(Comparator.comparingInt(VideoAccumulator::docFreq).reversed())
                .thenComparing(VideoAccumulator::bvid);

        private final String bvid;
        private String title = "";
        private long ownerMid = -1L;
        private String ownerName = "";
        private int docFreq;
        private double score;
        private double bestScore;

        private VideoAccumulator(String bvid) {
            this.bvid = bvid;
        }

        private void add(String title, long ownerMid, String ownerName, double contribution) {
            docFreq++;
            score += contribution;
            if (contribution >= bestScore) {
                bestScore = contribution;
                this.title = title;
                this.ownerMid = ownerMid;
                this.ownerName = ownerName;
            }
        }

        private String bvid() {
            return bvid;
        }

        private double score() {
            return bestScore + Math.max(0.0d, score - bestScore) * 0.28d + (Math.log1p(docFreq) * 3.0d);
        }

        private long ownerMid() {
            return ownerMid;
        }

        private int docFreq() {
            return docFreq;
        }

        private RelatedVideoResult toResult() {
            return new RelatedVideoResult(bvid, title, ownerMid, ownerName, docFreq, (float) score());
        }
    }

    private static final class OwnerAccumulator {
        private static final Comparator<OwnerAccumulator> ORDER = Comparator
                .comparingDouble(OwnerAccumulator::score).reversed()
                .thenComparing(Comparator.comparingInt(OwnerAccumulator::docFreq).reversed())
                .thenComparing(OwnerAccumulator::displayName);

        private final long mid;
        private final Map<String, Double> aliasScores = new HashMap<>();
        private final List<Double> contributions = new ArrayList<>();
        private int docFreq;

        private OwnerAccumulator(long mid) {
            this.mid = mid;
        }

        private void add(String ownerName, double contribution) {
            docFreq++;
            contributions.add(contribution);
            aliasScores.merge(ownerName, contribution, Double::sum);
        }

        private double score() {
            List<Double> ranked = new ArrayList<>(contributions);
            ranked.sort(Comparator.reverseOrder());
            double total = 0.0d;
            double decay = 1.0d;
            for (double contribution : ranked) {
                total += contribution * decay;
                decay *= 0.68d;
            }
            return total + (Math.log1p(docFreq) * 4.0d);
        }

        private int docFreq() {
            return docFreq;
        }

        private long mid() {
            return mid;
        }

        private String displayName() {
            return aliasScores.entrySet().stream()
                    .max(Map.Entry.<String, Double>comparingByValue().thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElse(Long.toString(mid));
        }

        private RelatedOwnerResult toResult() {
            return new RelatedOwnerResult(mid, displayName(), docFreq, (float) score());
        }
    }
}