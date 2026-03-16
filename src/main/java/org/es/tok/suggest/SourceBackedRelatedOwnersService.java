package org.es.tok.suggest;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
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
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SourceBackedRelatedOwnersService {

    private static final String OWNER_NAME_SOURCE_PATH = "owner.name";
    private static final String OWNER_MID_SOURCE_PATH = "owner.mid";
    private static final String STAT_SCORE_SOURCE_PATH = "stat_score";
    private static final String STAT_VIEW_SOURCE_PATH = "stat.view";
    private static final String INSERT_AT_SOURCE_PATH = "insert_at";

    private final SourceBackedAssociateSuggester associateSuggester;

    public SourceBackedRelatedOwnersService() {
        this(new SourceBackedAssociateSuggester());
    }

    SourceBackedRelatedOwnersService(SourceBackedAssociateSuggester associateSuggester) {
        this.associateSuggester = associateSuggester;
    }

    public List<RelatedOwnerResult> searchRelatedOwners(
            Engine.Searcher searcher,
            IndexService indexService,
            Collection<String> fields,
            String text,
            int size,
            int scanLimit) throws IOException {
        String sanitizedText = RelatedOwnerQueryTuning.sanitizeQueryText(text);
        if (sanitizedText.isBlank()) {
            return List.of();
        }

        List<FieldContext> fieldContexts = resolveFieldContexts(indexService, fields);
        if (fieldContexts.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> seedTerms = analyzeSeedTerms(fieldContexts, sanitizedText);
        if (seedTerms.isEmpty()) {
            String normalized = TextNormalization.normalizeLower(sanitizedText);
            if (RelatedOwnerQueryTuning.isUsefulSeedTerm(normalized)) {
                seedTerms.add(normalized);
            }
        }
        List<String> selectedTerms = selectQueryTerms(seedTerms, sanitizedText);
        if (selectedTerms.isEmpty()) {
            return List.of();
        }
        List<SeedTermProfile> seedTermProfiles = buildSeedTermProfiles(searcher, fieldContexts, selectedTerms);
        List<String> expansionTerms = RelatedOwnerQueryTuning.shouldExpandTopicTerms(sanitizedText, selectedTerms.size())
            ? expandTopicTerms(searcher, indexService, fields, sanitizedText, seedTerms, scanLimit)
            : List.of();

        TopDocs topDocs = null;
        for (RelatedOwnerQueryTuning.QueryPlan plan : RelatedOwnerQueryTuning.buildQueryPlans(sanitizedText, selectedTerms.size())) {
            Query query = buildTopicQuery(fieldContexts, seedTermProfiles, expansionTerms, plan.minimumSeedMatches());
            if (query == null) {
                continue;
            }
            TopDocs candidateTopDocs = searcher.search(
                    query,
                    RelatedOwnerQueryTuning.candidateDocLimit(size, scanLimit, selectedTerms.size(), plan.minimumSeedMatches()));
            if (candidateTopDocs.scoreDocs.length > 0) {
                topDocs = candidateTopDocs;
                break;
            }
        }
        if (topDocs == null || topDocs.scoreDocs.length == 0) {
            return List.of();
        }

        SourceProvider sourceProvider = SourceProvider.fromLookup(
                indexService.mapperService().mappingLookup(),
                null,
                SourceFieldMetrics.NOOP);
        Map<Long, RelatedOwnerAccumulator> owners = new LinkedHashMap<>();
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        long nowEpochSeconds = RelatedOwnerQueryTuning.nowEpochSeconds();
        for (int rank = 0; rank < topDocs.scoreDocs.length; rank++) {
            ScoreDoc scoreDoc = topDocs.scoreDocs[rank];
            int leafIndex = ReaderUtil.subIndex(scoreDoc.doc, leaves);
            LeafReaderContext leaf = leaves.get(leafIndex);
            int leafDocId = scoreDoc.doc - leaf.docBase;
            Source source = sourceProvider.getSource(leaf, leafDocId);
            collectOwnerHit(owners, scoreDoc.doc, source, fieldContexts, seedTermProfiles, nowEpochSeconds, scoreDoc.score, rank);
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
            List<FieldContext> fieldContexts,
            List<SeedTermProfile> seedTermProfiles,
            long nowEpochSeconds,
            float hitScore,
            int rank) throws IOException {
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
        SeedTermMatch seedTermMatch = matchSeedTerms(fieldContexts, source, seedTermProfiles);
        RelatedOwnerDocSignals docSignals = RelatedOwnerQueryTuning.docSignals(
            nowEpochSeconds,
            hitScore,
            rank,
            statScore,
            viewCount,
            insertAt,
            seedTermMatch.coverage(),
            seedTermMatch.matchedTermCount(),
            seedTermMatch.matchedStrongTermCount());
        int totalSeedTermCount = Math.max(1, seedTermProfiles.size());
        owners.computeIfAbsent(mid, ignored -> new RelatedOwnerAccumulator(mid, totalSeedTermCount))
            .add(docId, ownerName, docSignals, seedTermMatch);
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

    private Query buildTopicQuery(
            List<FieldContext> fieldContexts,
            List<SeedTermProfile> seedTermProfiles,
            List<String> expansionTerms,
            int minimumSeedMatches) {
        BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();
        for (FieldContext fieldContext : fieldContexts) {
            BooleanQuery.Builder fieldQuery = new BooleanQuery.Builder();
            int seedClauseCount = 0;
            for (SeedTermProfile seedTermProfile : seedTermProfiles) {
                if (!seedTermProfile.term().isBlank()) {
                    fieldQuery.add(
                            new BoostQuery(
                                    new TermQuery(new Term(fieldContext.indexField(), seedTermProfile.term())),
                                    seedTermProfile.exactQueryBoost()),
                            BooleanClause.Occur.SHOULD);
                    fieldQuery.add(
                            new BoostQuery(
                                    new PrefixQuery(new Term(fieldContext.indexField(), seedTermProfile.term())),
                                    seedTermProfile.prefixQueryBoost()),
                            BooleanClause.Occur.SHOULD);
                    seedClauseCount++;
                }
            }
            for (String expansionTerm : expansionTerms) {
                if (expansionTerm == null || expansionTerm.isBlank()) {
                    continue;
                }
                fieldQuery.add(
                    new BoostQuery(new PrefixQuery(new Term(fieldContext.indexField(), expansionTerm)), RelatedOwnerQueryTuning.EXPANSION_TERM_BOOST),
                        BooleanClause.Occur.SHOULD);
            }
            if (seedClauseCount > 1 && minimumSeedMatches > 1) {
                fieldQuery.setMinimumNumberShouldMatch(Math.min(seedClauseCount, minimumSeedMatches));
            }
            BooleanQuery builtFieldQuery = fieldQuery.build();
            if (!builtFieldQuery.clauses().isEmpty()) {
                queryBuilder.add(builtFieldQuery, BooleanClause.Occur.SHOULD);
            }
        }
        BooleanQuery query = queryBuilder.build();
        return query.clauses().isEmpty() ? null : query;
    }

    private List<String> expandTopicTerms(
            Engine.Searcher searcher,
            IndexService indexService,
            Collection<String> fields,
            String text,
            LinkedHashSet<String> seedTerms,
            int scanLimit) throws IOException {
        List<LuceneIndexSuggester.SuggestionOption> suggestions = associateSuggester.suggestAssociate(
                searcher,
                indexService,
                fields,
                text,
            new CompletionConfig(
                RelatedOwnerQueryTuning.MAX_EXPANSION_TERMS * 2,
                Math.min(RelatedOwnerQueryTuning.MAX_EXPANSION_SCAN_LIMIT, Math.max(24, scanLimit)),
                1,
                1,
                true,
                false));
        List<String> expansions = new ArrayList<>();
        for (LuceneIndexSuggester.SuggestionOption suggestion : suggestions) {
            String candidate = TextNormalization.normalizeLower(suggestion.text());
            if (!isUsefulExpansionTerm(candidate, seedTerms)) {
                continue;
            }
            expansions.add(candidate);
            if (expansions.size() >= RelatedOwnerQueryTuning.MAX_EXPANSION_TERMS) {
                break;
            }
        }
        return expansions;
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
        return TopicQualityHeuristics.filterOwnerSeedTerms(seedTerms);
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
                String normalized = TextNormalization.normalizeLower(termAttribute.toString());
                if (!normalized.isBlank()) {
                    tokens.add(normalized);
                }
            }
            tokenStream.end();
        }
        return tokens;
    }

    private static List<String> selectQueryTerms(LinkedHashSet<String> seedTerms, String text) {
        List<String> orderedTerms = new ArrayList<>(seedTerms);
        orderedTerms.sort(Comparator
                .comparingInt(RelatedOwnerQueryTuning::seedTermPriority).reversed()
                .thenComparing(Comparator.comparingInt(String::length).reversed())
                .thenComparing(String::compareTo));
        List<String> selected = new ArrayList<>();
        int maxQueryTerms = RelatedOwnerQueryTuning.maxQueryTerms(text, orderedTerms.size());
        for (String term : orderedTerms) {
            if (!RelatedOwnerQueryTuning.isUsefulSeedTerm(term)) {
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
                if (selected.size() >= maxQueryTerms) {
                    break;
                }
            }
        }
        return selected;
    }

    private static List<SeedTermProfile> buildSeedTermProfiles(
            Engine.Searcher searcher,
            List<FieldContext> fieldContexts,
            List<String> selectedTerms) throws IOException {
        if (selectedTerms.isEmpty()) {
            return List.of();
        }
        int maxDoc = Math.max(1, searcher.getIndexReader().maxDoc());
        List<SeedTermProfile> weighted = new ArrayList<>(selectedTerms.size());
        for (int index = 0; index < selectedTerms.size(); index++) {
            String term = selectedTerms.get(index);
            float baseBoost = RelatedOwnerQueryTuning.seedTermBoost(term, index);
            boolean strongSignal = RelatedOwnerQueryTuning.isStrongSeedTerm(term);
            double specificityWeight = specificityWeight(searcher, fieldContexts, term, maxDoc);
            double rankingWeight = baseBoost * specificityWeight;
            weighted.add(new SeedTermProfile(
                    term,
                    rankingWeight,
                    strongSignal,
                    RelatedOwnerQueryTuning.exactSeedTermBoost(baseBoost, strongSignal, specificityWeight),
                    RelatedOwnerQueryTuning.prefixSeedTermBoost(baseBoost, strongSignal, specificityWeight)));
        }
        List<SeedTermProfile> discriminative = weighted.stream()
            .filter(profile -> profile.strongSignal()
                || profile.term().codePointCount(0, profile.term().length()) >= RelatedOwnerQueryTuning.discriminativeTermLengthThreshold())
                .toList();
        List<SeedTermProfile> chosen = discriminative.isEmpty()
            ? weighted.stream().limit(Math.min(RelatedOwnerQueryTuning.fallbackSeedProfileLimit(), weighted.size())).toList()
                : discriminative;
        chosen = chosen.stream()
                .sorted(Comparator.comparingDouble(SeedTermProfile::weight).reversed())
                .toList();
        double totalWeight = chosen.stream().mapToDouble(SeedTermProfile::weight).sum();
        if (totalWeight <= 0.0d) {
            return List.of();
        }
        List<SeedTermProfile> normalized = new ArrayList<>(chosen.size());
        for (SeedTermProfile profile : chosen) {
            normalized.add(new SeedTermProfile(
                    profile.term(),
                    profile.weight() / totalWeight,
                    profile.strongSignal(),
                    profile.exactQueryBoost(),
                    profile.prefixQueryBoost()));
        }
        return List.copyOf(normalized);
    }

    private static double specificityWeight(
            Engine.Searcher searcher,
            List<FieldContext> fieldContexts,
            String term,
            int maxDoc) throws IOException {
        int totalDocFreq = 0;
        Set<String> seenFields = new HashSet<>();
        for (FieldContext fieldContext : fieldContexts) {
            if (!seenFields.add(fieldContext.indexField())) {
                continue;
            }
            totalDocFreq += Math.max(0, searcher.getIndexReader().docFreq(new Term(fieldContext.indexField(), term)));
        }
        if (totalDocFreq <= 0) {
            return 2.4d;
        }
        double inverseDensity = Math.log1p((double) maxDoc / (totalDocFreq + 1.0d));
        return Math.max(1.0d, Math.min(2.6d, 1.0d + (inverseDensity * 0.32d)));
    }

    private SeedTermMatch matchSeedTerms(
            List<FieldContext> fieldContexts,
            Source source,
            List<SeedTermProfile> seedTermProfiles) throws IOException {
        if (seedTermProfiles.isEmpty()) {
            return SeedTermMatch.empty();
        }
        Map<String, SeedTermProfile> unmatchedProfiles = new LinkedHashMap<>();
        for (SeedTermProfile profile : seedTermProfiles) {
            unmatchedProfiles.put(profile.term(), profile);
        }

        for (FieldContext fieldContext : fieldContexts) {
            Object rawValue = source.extractValue(fieldContext.sourcePath(), null);
            for (String value : SourceValueUtils.flattenStringValues(rawValue)) {
                if (value == null || value.isBlank() || unmatchedProfiles.isEmpty()) {
                    continue;
                }
                consumeMatchingTerms(fieldContext.analyzer(), fieldContext.indexField(), value, unmatchedProfiles);
                if (unmatchedProfiles.isEmpty()) {
                    break;
                }
            }
            if (unmatchedProfiles.isEmpty()) {
                break;
            }
        }

        LinkedHashSet<String> matchedTerms = new LinkedHashSet<>();
        double coverage = 0.0d;
        int matchedStrongTermCount = 0;
        for (SeedTermProfile profile : seedTermProfiles) {
            if (unmatchedProfiles.containsKey(profile.term())) {
                continue;
            }
            matchedTerms.add(profile.term());
            coverage += profile.weight();
            if (profile.strongSignal()) {
                matchedStrongTermCount++;
            }
        }
        return new SeedTermMatch(matchedTerms, Math.min(1.0d, coverage), matchedTerms.size(), matchedStrongTermCount);
    }

    private static void consumeMatchingTerms(
            Analyzer analyzer,
            String field,
            String text,
            Map<String, SeedTermProfile> unmatchedProfiles) throws IOException {
        try (TokenStream tokenStream = analyzer.tokenStream(field, text)) {
            CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                String normalized = TextNormalization.normalizeLower(termAttribute.toString());
                if (!normalized.isBlank()) {
                    unmatchedProfiles.remove(normalized);
                    if (unmatchedProfiles.isEmpty()) {
                        break;
                    }
                }
            }
            tokenStream.end();
        }
    }

    private static boolean isUsefulExpansionTerm(String candidate, LinkedHashSet<String> seedTerms) {
        if (candidate == null || candidate.isBlank() || seedTerms.contains(candidate)) {
            return false;
        }
        for (String seedTerm : seedTerms) {
            if (seedTerm == null || seedTerm.isBlank()) {
                continue;
            }
            if (candidate.contains(seedTerm) || seedTerm.contains(candidate)) {
                return false;
            }
        }
        return RelatedOwnerQueryTuning.isUsefulSeedTerm(candidate)
            && TopicQualityHeuristics.isOwnerSeedTermContextuallyAllowed(candidate, seedTerms);
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

    private static String normalizeOwnerName(String value) {
        return TextNormalization.normalizeOwnerDisplayName(value);
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

    record RelatedOwnerDocSignals(
            double topicWeight,
            double rankingWeight,
            double representativeSignal,
            double qualitySignal,
            double influenceSignal,
            double termCoverage,
            int matchedTermCount,
            int matchedStrongTermCount) {
    }

    private record RelatedOwnerDocContribution(
            String ownerName,
            double topicWeight,
            double rankingWeight,
            double representativeSignal,
            double qualitySignal,
            double influenceSignal,
            double termCoverage,
            int matchedTermCount,
            int matchedStrongTermCount,
            java.util.Set<String> matchedTerms) {

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
        private final int totalSeedTermCount;
        private final Map<Integer, RelatedOwnerDocContribution> contributionsByDoc = new HashMap<>();

        private RelatedOwnerAccumulator(long mid, int totalSeedTermCount) {
            this.mid = mid;
            this.totalSeedTermCount = Math.max(1, totalSeedTermCount);
        }

        private void add(int docId, String ownerName, RelatedOwnerDocSignals docSignals, SeedTermMatch seedTermMatch) {
            RelatedOwnerDocContribution contribution = new RelatedOwnerDocContribution(
                    ownerName,
                    docSignals.topicWeight(),
                    docSignals.rankingWeight(),
                    docSignals.representativeSignal(),
                    docSignals.qualitySignal(),
                docSignals.influenceSignal(),
                docSignals.termCoverage(),
                docSignals.matchedTermCount(),
                docSignals.matchedStrongTermCount(),
                seedTermMatch.matchedTerms());
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
            double maxTermCoverage = contributions.stream().mapToDouble(RelatedOwnerDocContribution::termCoverage).max().orElse(0.0d);
            double totalTermCoverage = contributions.stream().mapToDouble(RelatedOwnerDocContribution::termCoverage).sum();
                double topTermCoverage = contributions.stream()
                    .limit(3)
                    .mapToDouble(RelatedOwnerDocContribution::termCoverage)
                    .sum();
            double averageMatchedTermCount = contributions.stream()
                    .limit(4)
                    .mapToDouble(RelatedOwnerDocContribution::matchedTermCount)
                    .average()
                    .orElse(0.0d);
            LinkedHashSet<String> matchedSeedTerms = new LinkedHashSet<>();
            for (RelatedOwnerDocContribution contribution : contributions) {
                matchedSeedTerms.addAll(contribution.matchedTerms());
            }
            long multiTermDocs = contributions.stream()
                    .filter(contribution -> contribution.matchedTermCount() >= 2)
                    .count();
            int matchedStrongTerms = contributions.stream()
                    .mapToInt(RelatedOwnerDocContribution::matchedStrongTermCount)
                    .max()
                    .orElse(0);
            double maxQuality = contributions.stream().mapToDouble(RelatedOwnerDocContribution::qualitySignal).max().orElse(0.0d);
            double maxInfluence = contributions.stream().mapToDouble(RelatedOwnerDocContribution::influenceSignal).max().orElse(0.0d);
            double coverageDepth = Math.log1p(docFreq());
            double seedCoverageRatio = Math.min(1.0d, matchedSeedTerms.size() / (double) totalSeedTermCount);
                return (bestRepresentative * (88.0d + (seedCoverageRatio * 34.0d)))
                    + (secondRepresentative * (40.0d + (seedCoverageRatio * 16.0d)))
                    + (thirdRepresentative * 18.0d)
                    + (maxTopicWeight * 34.0d)
                    + (topCoverageWeight * 28.0d)
                    + (averageCoverageRepresentative * 16.0d)
                    + (Math.log1p(totalTopicWeight) * 16.0d)
                    + (maxTermCoverage * 14.0d)
                    + (topTermCoverage * 18.0d)
                    + (Math.log1p(totalTermCoverage) * 8.0d)
                    + (seedCoverageRatio * 96.0d)
                    + (matchedSeedTerms.size() * 10.0d)
                    + (matchedStrongTerms * 9.0d)
                    + (multiTermDocs * 9.0d)
                    + (averageMatchedTermCount * 6.0d)
                    + (maxQuality * 12.0d)
                    + (maxInfluence * 11.0d)
                    + (coverageDepth * 34.0d);
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

        private record SeedTermProfile(
            String term,
            double weight,
            boolean strongSignal,
            float exactQueryBoost,
            float prefixQueryBoost) {
    }

    private record SeedTermMatch(java.util.Set<String> matchedTerms, double coverage, int matchedTermCount, int matchedStrongTermCount) {
        private static SeedTermMatch empty() {
            return new SeedTermMatch(java.util.Set.of(), 0.0d, 0, 0);
        }
    }
}
