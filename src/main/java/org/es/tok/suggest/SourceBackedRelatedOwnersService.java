package org.es.tok.suggest;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.SourceFieldMetrics;
import org.elasticsearch.search.lookup.Source;
import org.elasticsearch.search.lookup.SourceProvider;

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

public class SourceBackedRelatedOwnersService {

    private static final String OWNER_NAME_SOURCE_PATH = "owner.name";
    private static final String OWNER_MID_SOURCE_PATH = "owner.mid";
    private static final String STAT_SCORE_SOURCE_PATH = "stat_score";
    private static final String STAT_VIEW_SOURCE_PATH = "stat.view";
    private static final String INSERT_AT_SOURCE_PATH = "insert_at";

    public List<RelatedOwnerResult> searchRelatedOwners(
            Engine.Searcher searcher,
            IndexService indexService,
            Collection<String> fields,
            String text,
            int size,
            int scanLimit) throws IOException {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<FieldContext> fieldContexts = resolveFieldContexts(indexService, fields);
        if (fieldContexts.isEmpty()) {
            return List.of();
        }

        Query query = buildTopicQuery(fieldContexts, text);
        if (query == null) {
            return List.of();
        }

        TopDocs topDocs = searcher.search(query, Math.max(scanLimit, size * 24));
        if (topDocs.scoreDocs.length == 0) {
            return List.of();
        }

        SourceProvider sourceProvider = SourceProvider.fromLookup(
                indexService.mapperService().mappingLookup(),
                null,
                SourceFieldMetrics.NOOP);
        Map<Long, RelatedOwnerAccumulator> owners = new LinkedHashMap<>();
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        long nowEpochSeconds = Instant.now().getEpochSecond();
        for (int rank = 0; rank < topDocs.scoreDocs.length; rank++) {
            ScoreDoc scoreDoc = topDocs.scoreDocs[rank];
            int leafIndex = ReaderUtil.subIndex(scoreDoc.doc, leaves);
            LeafReaderContext leaf = leaves.get(leafIndex);
            int leafDocId = scoreDoc.doc - leaf.docBase;
            Source source = sourceProvider.getSource(leaf, leafDocId);
            collectOwnerHit(owners, scoreDoc.doc, source, nowEpochSeconds, scoreDoc.score, rank);
        }

        return owners.values().stream()
                .sorted(RelatedOwnerAccumulator.ORDER)
                .limit(size)
                .map(RelatedOwnerAccumulator::toResult)
                .toList();
    }

    private void collectOwnerHit(
            Map<Long, RelatedOwnerAccumulator> owners,
            int docId,
            Source source,
            long nowEpochSeconds,
            float hitScore,
            int rank) {
        Long mid = asLong(source.extractValue(OWNER_MID_SOURCE_PATH, null));
        if (mid == null) {
            return;
        }

        String ownerName = normalizeOwnerName(asString(source.extractValue(OWNER_NAME_SOURCE_PATH, null)));
        if (ownerName.isBlank()) {
            return;
        }

        double statScore = asDouble(source.extractValue(STAT_SCORE_SOURCE_PATH, null));
        long viewCount = asLong(source.extractValue(STAT_VIEW_SOURCE_PATH, null), 0L);
        long insertAt = asLong(source.extractValue(INSERT_AT_SOURCE_PATH, null), 0L);
        RelatedOwnerDocSignals docSignals = docSignals(nowEpochSeconds, hitScore, rank, statScore, viewCount, insertAt);
        owners.computeIfAbsent(mid, RelatedOwnerAccumulator::new)
                .add(docId, ownerName, docSignals);
    }

    private static RelatedOwnerDocSignals docSignals(
            long nowEpochSeconds,
            float hitScore,
            int rank,
            double statScore,
            long viewCount,
            long insertAt) {
        double topicWeight = ((Math.log1p(Math.max(1.0f, hitScore)) + 1.0d) * 1.35d) / (1.0d + (rank * 0.05d));
        double normalizedStatScore = Math.max(0.0d, statScore);
        double quality = Math.log1p(normalizedStatScore * 2400.0d) * 3.6d;
        double influence = Math.log1p(Math.max(0L, viewCount)) * 3.1d;
        double ageDays = ageDays(nowEpochSeconds, insertAt);
        double recencyFactor = 1.0d / (1.0d + (ageDays / 21.0d));
        double freshness = recencyFactor * 1.4d;
        double representativeSignal = topicWeight * ((quality * 1.05d) + (influence * 1.35d) + freshness + 1.0d);
        double rankingWeight = representativeSignal + (topicWeight * 3.0d) + (quality * 0.25d) + (influence * 0.2d);
        return new RelatedOwnerDocSignals(topicWeight, rankingWeight, representativeSignal, quality, influence);
    }

    private static double ageDays(long nowEpochSeconds, long insertAt) {
        if (insertAt <= 0L || nowEpochSeconds <= 0L) {
            return 3650.0d;
        }
        return Math.max(0.0d, (nowEpochSeconds - insertAt) / 86400.0d);
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

    private Query buildTopicQuery(List<FieldContext> fieldContexts, String text) throws IOException {
        LinkedHashSet<String> seedTerms = analyzeSeedTerms(fieldContexts, text);
        if (seedTerms.isEmpty()) {
            seedTerms.add(normalize(text));
        }
        List<String> selectedTerms = selectQueryTerms(seedTerms);
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

    private static List<String> selectQueryTerms(LinkedHashSet<String> seedTerms) {
        List<String> orderedTerms = new ArrayList<>(seedTerms);
        orderedTerms.sort(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo));
        List<String> selected = new ArrayList<>();
        for (String term : orderedTerms) {
            if (term == null || term.isBlank()) {
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
        return selected;
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
        if (field.endsWith(".assoc")) {
            return field.substring(0, field.length() - ".assoc".length());
        }
        return field;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeOwnerName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String collapsed = String.join(" ", value.trim().split("\\s+"));
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
        return collapsed;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
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

    private static Long asLong(Object value) {
        return asLong(value, null);
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

    private record RelatedOwnerDocSignals(
            double topicWeight,
            double rankingWeight,
            double representativeSignal,
            double qualitySignal,
            double influenceSignal) {
    }

    private record RelatedOwnerDocContribution(
            String ownerName,
            double topicWeight,
            double rankingWeight,
            double representativeSignal,
            double qualitySignal,
            double influenceSignal) {

        private RelatedOwnerDocContribution preferBetter(RelatedOwnerDocContribution other) {
            if (other == null) {
                return this;
            }
            if (other.representativeSignal() > representativeSignal()) {
                return other;
            }
            if (other.representativeSignal() < representativeSignal()) {
                return this;
            }
            if (other.rankingWeight() > rankingWeight()) {
                return other;
            }
            if (other.rankingWeight() < rankingWeight()) {
                return this;
            }
            return other.ownerName().compareTo(ownerName()) >= 0 ? other : this;
        }
    }

    private static final class RelatedOwnerAccumulator {
        private static final Comparator<RelatedOwnerAccumulator> ORDER = Comparator
                .comparingDouble(RelatedOwnerAccumulator::score).reversed()
                .thenComparing(Comparator.comparingInt(RelatedOwnerAccumulator::docFreq).reversed())
                .thenComparing(RelatedOwnerAccumulator::displayName);

        private final long mid;
        private final Map<Integer, RelatedOwnerDocContribution> contributionsByDoc = new HashMap<>();

        private RelatedOwnerAccumulator(long mid) {
            this.mid = mid;
        }

        private void add(int docId, String ownerName, RelatedOwnerDocSignals docSignals) {
            RelatedOwnerDocContribution contribution = new RelatedOwnerDocContribution(
                    ownerName,
                    docSignals.topicWeight(),
                    docSignals.rankingWeight(),
                    docSignals.representativeSignal(),
                    docSignals.qualitySignal(),
                    docSignals.influenceSignal());
            contributionsByDoc.merge(docId, contribution, RelatedOwnerDocContribution::preferBetter);
        }

        private double score() {
            if (contributionsByDoc.isEmpty()) {
                return 0.0d;
            }
            List<RelatedOwnerDocContribution> contributions = contributionsByDoc.values().stream()
                    .sorted(Comparator
                            .comparingDouble(RelatedOwnerDocContribution::representativeSignal).reversed()
                            .thenComparing(Comparator.comparingDouble(RelatedOwnerDocContribution::rankingWeight).reversed()))
                    .toList();
            double bestRepresentative = contributions.get(0).representativeSignal();
            double secondRepresentative = contributions.size() > 1 ? contributions.get(1).representativeSignal() : 0.0d;
                        double thirdRepresentative = contributions.size() > 2 ? contributions.get(2).representativeSignal() : 0.0d;
            double maxTopicWeight = contributions.stream().mapToDouble(RelatedOwnerDocContribution::topicWeight).max().orElse(0.0d);
            double totalTopicWeight = contributions.stream().mapToDouble(RelatedOwnerDocContribution::topicWeight).sum();
                        double topCoverageWeight = contributions.stream()
                            .limit(3)
                            .mapToDouble(RelatedOwnerDocContribution::topicWeight)
                            .sum();
                        double averageCoverageRepresentative = contributions.stream()
                            .limit(3)
                            .mapToDouble(RelatedOwnerDocContribution::representativeSignal)
                            .average()
                            .orElse(0.0d);
            double maxQuality = contributions.stream().mapToDouble(RelatedOwnerDocContribution::qualitySignal).max().orElse(0.0d);
            double maxInfluence = contributions.stream().mapToDouble(RelatedOwnerDocContribution::influenceSignal).max().orElse(0.0d);
                        double coverageDepth = Math.log1p(docFreq());
                        return (bestRepresentative * 112.0d)
                            + (secondRepresentative * 52.0d)
                            + (thirdRepresentative * 24.0d)
                            + (maxTopicWeight * 34.0d)
                            + (topCoverageWeight * 28.0d)
                            + (averageCoverageRepresentative * 16.0d)
                            + (Math.log1p(totalTopicWeight) * 16.0d)
                    + (maxQuality * 12.0d)
                    + (maxInfluence * 16.0d)
                            + (coverageDepth * 28.0d);
        }

        private int docFreq() {
            return contributionsByDoc.size();
        }

        private String displayName() {
            Map<String, Double> aliasScores = new HashMap<>();
            for (RelatedOwnerDocContribution contribution : contributionsByDoc.values()) {
                aliasScores.merge(contribution.ownerName(), contribution.rankingWeight(), Double::sum);
            }
            return aliasScores.entrySet().stream()
                    .max(Map.Entry.<String, Double>comparingByValue()
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElse(Long.toString(mid));
        }

        private RelatedOwnerResult toResult() {
            return new RelatedOwnerResult(mid, displayName(), docFreq(), (float) score());
        }
    }

    public record RelatedOwnerResult(long mid, String name, int docFreq, float score) {
    }
}
