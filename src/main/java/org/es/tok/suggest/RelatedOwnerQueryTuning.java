package org.es.tok.suggest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.es.tok.text.TopicQualityHeuristics;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class RelatedOwnerQueryTuning {
    private static final QueryTuningProfile PROFILE = loadProfile();
    static final float EXPANSION_TERM_BOOST = PROFILE.expansionTermBoost();
    static final int MAX_EXPANSION_TERMS = PROFILE.maxExpansionTerms();
    static final int MAX_EXPANSION_SCAN_LIMIT = PROFILE.maxExpansionScanLimit();

    static String sanitizeQueryText(String text) {
        return TopicQualityHeuristics.sanitizeQueryText(text);
    }

    static boolean isUsefulSeedTerm(String term) {
        return TopicQualityHeuristics.isUsefulOwnerQuerySeedTerm(term);
    }

    static int seedTermPriority(String term) {
        return TopicQualityHeuristics.ownerQuerySeedPriority(term);
    }

    static int maxQueryTerms(String text, int availableTermCount) {
        if (availableTermCount <= 0) {
            return 0;
        }
        int codePointLength = text == null ? 0 : text.codePointCount(0, text.length());
        boolean hasWhitespace = text != null && text.chars().anyMatch(Character::isWhitespace);
        if (hasWhitespace || codePointLength >= PROFILE.longQueryLengthThreshold()) {
            return Math.min(PROFILE.maxLongQueryTerms(), availableTermCount);
        }
        if (codePointLength >= PROFILE.mediumQueryLengthThreshold()) {
            return Math.min(PROFILE.maxMediumQueryTerms(), availableTermCount);
        }
        return Math.min(PROFILE.maxShortQueryTerms(), availableTermCount);
    }

    static List<QueryPlan> buildQueryPlans(String text, int selectedTermCount) {
        int primaryMinimumMatches = minimumSeedMatches(text, selectedTermCount);
        List<QueryPlan> plans = new ArrayList<>();
        for (int minimumMatches = primaryMinimumMatches; minimumMatches >= 1; minimumMatches--) {
            plans.add(new QueryPlan(minimumMatches));
        }
        return plans;
    }

    static boolean shouldExpandTopicTerms(String text, int selectedTermCount) {
        if (selectedTermCount <= 0) {
            return false;
        }
        if (selectedTermCount >= 5) {
            return false;
        }
        int codePointLength = text == null ? 0 : text.codePointCount(0, text.length());
        boolean hasWhitespace = text != null && text.chars().anyMatch(Character::isWhitespace);
        if (selectedTermCount <= 2) {
            return true;
        }
        return codePointLength < PROFILE.longQueryLengthThreshold() || (hasWhitespace && selectedTermCount <= 3);
    }

    static int candidateDocLimit(int size, int scanLimit, int selectedTermCount, int minimumSeedMatches) {
        int floor = minimumSeedMatches <= 1 ? PROFILE.candidateDocFloorSingleMatch() : PROFILE.candidateDocFloorMultiMatch();
        if (selectedTermCount >= 5) {
            floor = Math.max(floor, PROFILE.candidateDocFloorManyTerms());
        }
        int target = Math.max(size * 10, floor);
        return Math.min(Math.max(size, scanLimit), target);
    }

    static float seedTermBoost(String seedTerm, int rank) {
        int codePointLength = seedTerm.codePointCount(0, seedTerm.length());
        double lengthBoost = 1.0d + (Math.min(PROFILE.seedLengthBoostCap(), codePointLength) * PROFILE.seedLengthBoostPerUnit());
        double rankDecay = Math.max(PROFILE.seedRankDecayFloor(), 1.0d - (rank * PROFILE.seedRankDecayStep()));
        return (float) (lengthBoost * rankDecay);
    }

    static float exactSeedTermBoost(float baseSeedBoost, boolean strongSignal, double specificityWeight) {
        double signalFactor = strongSignal ? 1.12d : 1.0d;
        return (float) (baseSeedBoost * PROFILE.exactSeedTermBoostFactor() * signalFactor * specificityWeight);
    }

    static float prefixSeedTermBoost(float baseSeedBoost, boolean strongSignal, double specificityWeight) {
        double signalFactor = strongSignal ? 1.06d : 1.0d;
        return (float) (baseSeedBoost * PROFILE.prefixSeedTermBoostFactor() * signalFactor * Math.max(1.0d, specificityWeight * 0.85d));
    }

    static int discriminativeTermLengthThreshold() {
        return PROFILE.discriminativeTermLengthThreshold();
    }

    static int fallbackSeedProfileLimit() {
        return PROFILE.fallbackSeedProfileLimit();
    }

    static boolean isStrongSeedTerm(String term) {
        return TopicQualityHeuristics.isStrongOwnerQuerySeedTerm(term);
    }

    static SourceBackedRelatedOwnersService.RelatedOwnerDocSignals docSignals(
            long nowEpochSeconds,
            float hitScore,
            int rank,
            double statScore,
            long viewCount,
            long insertAt,
            double termCoverage,
            int matchedTermCount,
            int matchedStrongTermCount) {
        double topicWeight = ((Math.log1p(Math.max(1.0f, hitScore)) + 1.0d) * PROFILE.topicWeightMultiplier())
                / (1.0d + (rank * 0.05d));
        double normalizedStatScore = Math.max(0.0d, statScore);
        double quality = Math.log1p(normalizedStatScore * 2400.0d) * PROFILE.qualityMultiplier();
        double influence = Math.log1p(Math.max(0L, viewCount)) * PROFILE.influenceMultiplier();
        double ageDays = ageDays(nowEpochSeconds, insertAt);
        double recencyFactor = 1.0d / (1.0d + (ageDays / PROFILE.recencyHalflifeDays()));
        double freshness = recencyFactor * PROFILE.freshnessMultiplier();
        double relevanceMultiplier = 1.0d
                + (termCoverage * PROFILE.representativeTermCoverageFactor())
                + (matchedTermCount * PROFILE.representativeMatchedTermFactor())
                + (matchedStrongTermCount * PROFILE.representativeMatchedStrongTermFactor());
        double representativeSignal = topicWeight * ((quality * PROFILE.representativeQualityFactor())
            + (influence * PROFILE.representativeInfluenceFactor())
            + freshness
            + PROFILE.representativeBaseOffset()) * relevanceMultiplier;
        double rankingWeight = representativeSignal
            + (topicWeight * PROFILE.rankingTopicWeight())
            + (termCoverage * PROFILE.rankingTermCoverageWeight())
            + (matchedTermCount * PROFILE.rankingMatchedTermWeight())
            + (matchedStrongTermCount * PROFILE.rankingMatchedStrongTermWeight())
            + (quality * PROFILE.rankingQualityWeight())
            + (influence * PROFILE.rankingInfluenceWeight());
        return new SourceBackedRelatedOwnersService.RelatedOwnerDocSignals(
                topicWeight,
                rankingWeight,
                representativeSignal,
                quality,
                influence,
                termCoverage,
                matchedTermCount,
                matchedStrongTermCount);
    }

    static long nowEpochSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static int minimumSeedMatches(String text, int selectedTermCount) {
        if (selectedTermCount <= 1) {
            return 1;
        }

        int codePointLength = text == null ? 0 : text.codePointCount(0, text.length());
        boolean hasWhitespace = text != null && text.chars().anyMatch(Character::isWhitespace);
        if (selectedTermCount == 2) {
            return hasWhitespace || codePointLength >= 8 ? 1 : 2;
        }
        if (selectedTermCount <= 4) {
            return hasWhitespace || codePointLength >= PROFILE.minimumMatchMediumLengthThreshold()
                    ? PROFILE.mediumTermMinimumMatches()
                    : PROFILE.shortTermMinimumMatches();
        }
        if (hasWhitespace || codePointLength >= PROFILE.minimumMatchLongLengthThreshold()) {
            return Math.min(PROFILE.longTermMinimumMatches(), selectedTermCount);
        }
        return Math.min(
                PROFILE.manyTermMatchCap(),
                Math.max(2, (int) Math.ceil(selectedTermCount * PROFILE.manyTermMatchRatio())));
    }

    private static double ageDays(long nowEpochSeconds, long insertAt) {
        if (insertAt <= 0L || nowEpochSeconds <= 0L) {
            return 3650.0d;
        }
        return Math.max(0.0d, (nowEpochSeconds - insertAt) / 86400.0d);
    }

    private static QueryTuningProfile loadProfile() {
        try (InputStream inputStream = RelatedOwnerQueryTuning.class.getResourceAsStream("/tuning/related_owner_query_profile.json")) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing related owner query tuning profile resource");
            }
            Map<?, ?> root = new ObjectMapper().readValue(inputStream, Map.class);
            Map<?, ?> profile = (Map<?, ?>) root.get("profile");
            return new QueryTuningProfile(
                    floatValue(profile, "expansion_term_boost", 0.32f),
                    floatValue(profile, "exact_seed_term_boost_factor", 1.9f),
                    floatValue(profile, "prefix_seed_term_boost_factor", 0.72f),
                    intValue(profile, "max_expansion_terms", 3),
                    intValue(profile, "max_short_query_terms", 8),
                    intValue(profile, "max_medium_query_terms", 6),
                    intValue(profile, "max_long_query_terms", 5),
                    intValue(profile, "max_expansion_scan_limit", 48),
                    intValue(profile, "medium_query_length_threshold", 10),
                    intValue(profile, "long_query_length_threshold", 18),
                    intValue(profile, "minimum_match_medium_length_threshold", 12),
                    intValue(profile, "minimum_match_long_length_threshold", 18),
                    doubleValue(profile, "topic_weight_multiplier", 1.35d),
                    doubleValue(profile, "quality_multiplier", 3.6d),
                    doubleValue(profile, "influence_multiplier", 3.1d),
                    doubleValue(profile, "recency_halflife_days", 21.0d),
                    doubleValue(profile, "freshness_multiplier", 1.4d),
                    intValue(profile, "candidate_doc_floor_single_match", 64),
                    intValue(profile, "candidate_doc_floor_multi_match", 40),
                    intValue(profile, "candidate_doc_floor_many_terms", 56),
                    doubleValue(profile, "seed_length_boost_per_unit", 0.12d),
                    intValue(profile, "seed_length_boost_cap", 5),
                    doubleValue(profile, "seed_rank_decay_step", 0.06d),
                    doubleValue(profile, "seed_rank_decay_floor", 0.72d),
                    doubleValue(profile, "representative_quality_factor", 1.05d),
                    doubleValue(profile, "representative_influence_factor", 1.35d),
                    doubleValue(profile, "representative_term_coverage_factor", 1.15d),
                    doubleValue(profile, "representative_matched_term_factor", 0.24d),
                    doubleValue(profile, "representative_matched_strong_term_factor", 0.32d),
                    doubleValue(profile, "representative_base_offset", 1.0d),
                    doubleValue(profile, "ranking_topic_weight", 3.0d),
                    doubleValue(profile, "ranking_term_coverage_weight", 2.0d),
                    doubleValue(profile, "ranking_matched_term_weight", 0.4d),
                    doubleValue(profile, "ranking_matched_strong_term_weight", 0.6d),
                    doubleValue(profile, "ranking_quality_weight", 0.25d),
                    doubleValue(profile, "ranking_influence_weight", 0.2d),
                    intValue(profile, "discriminative_term_length_threshold", 5),
                    intValue(profile, "fallback_seed_profile_limit", 2),
                    doubleValue(profile, "many_term_match_ratio", 0.6d),
                    intValue(profile, "many_term_match_cap", 4),
                    intValue(profile, "medium_term_minimum_matches", 2),
                    intValue(profile, "short_term_minimum_matches", 3),
                    intValue(profile, "long_term_minimum_matches", 3));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load related owner query tuning profile", exception);
        }
    }

    private static int intValue(Map<?, ?> values, String key, int defaultValue) {
        Object value = values == null ? null : values.get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static float floatValue(Map<?, ?> values, String key, float defaultValue) {
        Object value = values == null ? null : values.get(key);
        return value instanceof Number number ? number.floatValue() : defaultValue;
    }

    private static double doubleValue(Map<?, ?> values, String key, double defaultValue) {
        Object value = values == null ? null : values.get(key);
        return value instanceof Number number ? number.doubleValue() : defaultValue;
    }

    record QueryPlan(int minimumSeedMatches) {
    }

    private record QueryTuningProfile(
            float expansionTermBoost,
            float exactSeedTermBoostFactor,
            float prefixSeedTermBoostFactor,
            int maxExpansionTerms,
            int maxShortQueryTerms,
            int maxMediumQueryTerms,
            int maxLongQueryTerms,
            int maxExpansionScanLimit,
            int mediumQueryLengthThreshold,
            int longQueryLengthThreshold,
            int minimumMatchMediumLengthThreshold,
            int minimumMatchLongLengthThreshold,
            double topicWeightMultiplier,
            double qualityMultiplier,
            double influenceMultiplier,
            double recencyHalflifeDays,
            double freshnessMultiplier,
            int candidateDocFloorSingleMatch,
            int candidateDocFloorMultiMatch,
            int candidateDocFloorManyTerms,
            double seedLengthBoostPerUnit,
            int seedLengthBoostCap,
            double seedRankDecayStep,
            double seedRankDecayFloor,
            double representativeQualityFactor,
            double representativeInfluenceFactor,
            double representativeTermCoverageFactor,
            double representativeMatchedTermFactor,
            double representativeMatchedStrongTermFactor,
            double representativeBaseOffset,
            double rankingTopicWeight,
            double rankingTermCoverageWeight,
            double rankingMatchedTermWeight,
            double rankingMatchedStrongTermWeight,
            double rankingQualityWeight,
            double rankingInfluenceWeight,
            int discriminativeTermLengthThreshold,
            int fallbackSeedProfileLimit,
            double manyTermMatchRatio,
            int manyTermMatchCap,
            int mediumTermMinimumMatches,
            int shortTermMinimumMatches,
            int longTermMinimumMatches) {
    }

    private RelatedOwnerQueryTuning() {
    }
}
