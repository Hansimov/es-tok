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
        SeedContext seedContext = loadSeedContext(searcher, indexService, relation, bvids, mids, scanLimit);
        if (seedContext.isEmpty()) {
            return RelationResult.empty();
        }

        Query candidateQuery = buildCandidateQuery(indexService, relation, seedContext);
        if (candidateQuery == null) {
            return RelationResult.empty();
        }

        TopDocs topDocs = searcher.search(candidateQuery, Math.max(scanLimit, size * 24));
        if (topDocs.scoreDocs.length == 0) {
            return RelationResult.empty();
        }

        SourceProvider sourceProvider = SourceProvider.fromLookup(
                indexService.mapperService().mappingLookup(),
                null,
                SourceFieldMetrics.NOOP);
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        long nowEpochSeconds = Instant.now().getEpochSecond();

        Map<String, VideoAccumulator> videos = new LinkedHashMap<>();
        Map<Long, OwnerAccumulator> owners = new LinkedHashMap<>();
        for (int rank = 0; rank < topDocs.scoreDocs.length; rank++) {
            ScoreDoc scoreDoc = topDocs.scoreDocs[rank];
            int leafIndex = ReaderUtil.subIndex(scoreDoc.doc, leaves);
            LeafReaderContext leaf = leaves.get(leafIndex);
            int leafDocId = scoreDoc.doc - leaf.docBase;
            Source source = sourceProvider.getSource(leaf, leafDocId);

            String bvid = normalizeString(source.extractValue(BVID_SOURCE_PATH, null));
            long ownerMid = asLong(source.extractValue(OWNER_MID_SOURCE_PATH, null), -1L);
            String ownerName = normalizeDisplay(source.extractValue(OWNER_NAME_SOURCE_PATH, null));
            String title = normalizeDisplay(source.extractValue(TITLE_SOURCE_PATH, null));
            if (bvid.isBlank() || ownerMid < 0L || title.isBlank()) {
                continue;
            }

            DocSignals signals = computeSignals(indexService, seedContext, source, scoreDoc.score, rank, nowEpochSeconds);
            if (signals.score() <= 0.0d) {
                continue;
            }

            if (EsTokEntityRelationRequest.RELATED_VIDEOS_BY_VIDEOS.equals(relation)
                    || EsTokEntityRelationRequest.RELATED_VIDEOS_BY_OWNERS.equals(relation)) {
                if (seedContext.seedBvids.contains(bvid)) {
                    continue;
                }
                videos.computeIfAbsent(bvid, ignored -> new VideoAccumulator(bvid))
                        .add(title, ownerMid, ownerName, signals.score());
            } else {
                if (EsTokEntityRelationRequest.RELATED_OWNERS_BY_OWNERS.equals(relation) && seedContext.seedOwnerMids.contains(ownerMid)) {
                    continue;
                }
                owners.computeIfAbsent(ownerMid, OwnerAccumulator::new)
                        .add(ownerName, signals.score());
            }
        }

        List<RelatedVideoResult> videoResults = videos.values().stream()
                .sorted(VideoAccumulator.ORDER)
                .limit(size)
                .map(VideoAccumulator::toResult)
                .toList();
        List<RelatedOwnerResult> ownerResults = owners.values().stream()
                .sorted(OwnerAccumulator.ORDER)
                .limit(size)
                .map(OwnerAccumulator::toResult)
                .toList();
        return new RelationResult(videoResults, ownerResults);
    }

    private SeedContext loadSeedContext(
            Engine.Searcher searcher,
            IndexService indexService,
            String relation,
            List<String> bvids,
            List<Long> mids,
            int scanLimit) throws IOException {
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
        List<FieldContext> topicFields = resolveTopicFields(indexService);
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        SeedContext seedContext = new SeedContext();
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            int leafIndex = ReaderUtil.subIndex(scoreDoc.doc, leaves);
            LeafReaderContext leaf = leaves.get(leafIndex);
            int leafDocId = scoreDoc.doc - leaf.docBase;
            Source source = sourceProvider.getSource(leaf, leafDocId);
            seedContext.addBvid(normalizeString(source.extractValue(BVID_SOURCE_PATH, null)));
            seedContext.addOwnerMid(asLong(source.extractValue(OWNER_MID_SOURCE_PATH, null), -1L));
            seedContext.addInsertAt(asLong(source.extractValue(INSERT_AT_SOURCE_PATH, null), 0L));
            seedContext.addTokens(extractTopicTokens(topicFields, source));
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
            return builder.build().clauses().isEmpty() ? null : builder.build();
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
        return builder.build().clauses().isEmpty() ? null : builder.build();
    }

    private Query buildCandidateQuery(IndexService indexService, String relation, SeedContext seedContext) {
        List<FieldContext> topicFields = resolveTopicFields(indexService);
        List<String> selectedTerms = selectQueryTerms(seedContext.tokens);

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (FieldContext fieldContext : topicFields) {
            for (String token : selectedTerms) {
                builder.add(new BoostQuery(new PrefixQuery(new Term(fieldContext.indexField(), token)), 2.2f), BooleanClause.Occur.SHOULD);
            }
        }
        for (Long mid : seedContext.seedOwnerMids) {
            if (mid != null && mid > 0L) {
                builder.add(new BoostQuery(LongPoint.newExactQuery("owner.mid", mid), 3.0f), BooleanClause.Occur.SHOULD);
            }
        }

        BooleanQuery query = builder.build();
        return query.clauses().isEmpty() ? null : query;
    }

    private DocSignals computeSignals(
            IndexService indexService,
            SeedContext seedContext,
            Source source,
            float hitScore,
            int rank,
            long nowEpochSeconds) throws IOException {
        List<FieldContext> topicFields = resolveTopicFields(indexService);
        Set<String> candidateTokens = extractTopicTokens(topicFields, source);
        int topicOverlap = 0;
        for (String token : candidateTokens) {
            if (seedContext.tokens.contains(token)) {
                topicOverlap++;
            }
        }

        long ownerMid = asLong(source.extractValue(OWNER_MID_SOURCE_PATH, null), -1L);
        double ownerBoost = seedContext.seedOwnerMids.contains(ownerMid) ? 1.0d : 0.0d;
        double hitWeight = ((Math.log1p(Math.max(1.0f, hitScore)) + 1.0d) / (1.0d + (rank * 0.06d)));
        double recency = recencyScore(nowEpochSeconds, seedContext.seedInsertAt, asLong(source.extractValue(INSERT_AT_SOURCE_PATH, null), 0L));
        double quality = Math.log1p(Math.max(0.0d, asDouble(source.extractValue(STAT_SCORE_SOURCE_PATH, null))) * 800.0d);
        double influence = Math.log1p(Math.max(0L, asLong(source.extractValue(STAT_VIEW_SOURCE_PATH, null), 0L)));
        double score = (topicOverlap * 18.0d) + (ownerBoost * 22.0d) + (hitWeight * 12.0d) + (recency * 6.0d) + (quality * 3.0d) + (influence * 1.6d);
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
        List<String> candidates = List.of("title.words", "tags.words", "desc.words");
        List<FieldContext> resolved = new ArrayList<>();
        for (String field : candidates) {
            MappedFieldType fieldType = indexService.mapperService().fieldType(field);
            if (fieldType == null) {
                continue;
            }
            resolved.add(new FieldContext(field, sourcePath(field), fieldType.getTextSearchInfo().searchAnalyzer()));
        }
        return resolved;
    }

    private Set<String> extractTopicTokens(List<FieldContext> fieldContexts, Source source) throws IOException {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (FieldContext fieldContext : fieldContexts) {
            Object rawValue = source.extractValue(fieldContext.sourcePath(), null);
            for (String value : flattenSourceValues(rawValue)) {
                for (String token : analyze(fieldContext.analyzer(), fieldContext.indexField(), value)) {
                    if (!token.isBlank()) {
                        tokens.add(token);
                    }
                }
            }
        }
        return tokens;
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

    private static List<String> selectQueryTerms(Collection<String> seedTerms) {
        List<String> ordered = new ArrayList<>(seedTerms);
        ordered.sort(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo));
        List<String> selected = new ArrayList<>();
        for (String token : ordered) {
            if (token == null || token.isBlank()) {
                continue;
            }
            boolean covered = false;
            for (String existing : selected) {
                if (existing.contains(token)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                selected.add(token);
            }
            if (selected.size() >= 8) {
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

    private record FieldContext(String indexField, String sourcePath, Analyzer analyzer) {
    }

    private record DocSignals(double score) {
    }

    public record RelatedVideoResult(String bvid, String title, long ownerMid, String ownerName, int docFreq, float score) {
    }

    public record RelatedOwnerResult(long mid, String name, int docFreq, float score) {
    }

    public record RelationResult(List<RelatedVideoResult> videos, List<RelatedOwnerResult> owners) {
        private static RelationResult empty() {
            return new RelationResult(List.of(), List.of());
        }
    }

    private static final class SeedContext {
        private final LinkedHashSet<String> seedBvids = new LinkedHashSet<>();
        private final LinkedHashSet<Long> seedOwnerMids = new LinkedHashSet<>();
        private final LinkedHashSet<String> tokens = new LinkedHashSet<>();
        private final List<Long> seedInsertAt = new ArrayList<>();

        private void addBvid(String bvid) {
            if (!bvid.isBlank()) {
                seedBvids.add(bvid);
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

        private void addTokens(Collection<String> tokens) {
            this.tokens.addAll(tokens);
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

        private void add(String title, long ownerMid, String ownerName, double score) {
            docFreq++;
            this.score += score;
            if (score >= bestScore) {
                bestScore = score;
                this.title = title;
                this.ownerMid = ownerMid;
                this.ownerName = ownerName;
            }
        }

        private String bvid() {
            return bvid;
        }

        private double score() {
            return score;
        }

        private int docFreq() {
            return docFreq;
        }

        private RelatedVideoResult toResult() {
            return new RelatedVideoResult(bvid, title, ownerMid, ownerName, docFreq, (float) score);
        }
    }

    private static final class OwnerAccumulator {
        private static final Comparator<OwnerAccumulator> ORDER = Comparator
                .comparingDouble(OwnerAccumulator::score).reversed()
                .thenComparing(Comparator.comparingInt(OwnerAccumulator::docFreq).reversed())
                .thenComparing(OwnerAccumulator::displayName);

        private final long mid;
        private final Map<String, Double> aliasScores = new HashMap<>();
        private int docFreq;
        private double score;

        private OwnerAccumulator(long mid) {
            this.mid = mid;
        }

        private void add(String ownerName, double contribution) {
            docFreq++;
            score += contribution;
            aliasScores.merge(ownerName, contribution, Double::sum);
        }

        private double score() {
            return score;
        }

        private int docFreq() {
            return docFreq;
        }

        private String displayName() {
            return aliasScores.entrySet().stream()
                    .max(Map.Entry.<String, Double>comparingByValue().thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElse(Long.toString(mid));
        }

        private RelatedOwnerResult toResult() {
            return new RelatedOwnerResult(mid, displayName(), docFreq, (float) score);
        }
    }
}