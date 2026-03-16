package org.es.tok.relations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.es.tok.action.EsTokEntityRelationRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

final class RelationTuning {
    private static final Map<String, RelationProfile> PROFILES = loadProfiles();
    private static final RelationProfile RELATED_VIDEOS_BY_VIDEOS = PROFILES.get(EsTokEntityRelationRequest.RELATED_VIDEOS_BY_VIDEOS);
    private static final RelationProfile RELATED_OWNERS_BY_VIDEOS = PROFILES.get(EsTokEntityRelationRequest.RELATED_OWNERS_BY_VIDEOS);
    private static final RelationProfile RELATED_VIDEOS_BY_OWNERS = PROFILES.get(EsTokEntityRelationRequest.RELATED_VIDEOS_BY_OWNERS);
    private static final RelationProfile RELATED_OWNERS_BY_OWNERS = PROFILES.get(EsTokEntityRelationRequest.RELATED_OWNERS_BY_OWNERS);

    static RelationProfile profile(String relation) {
        return switch (relation) {
            case EsTokEntityRelationRequest.RELATED_VIDEOS_BY_VIDEOS -> RELATED_VIDEOS_BY_VIDEOS;
            case EsTokEntityRelationRequest.RELATED_OWNERS_BY_VIDEOS -> RELATED_OWNERS_BY_VIDEOS;
            case EsTokEntityRelationRequest.RELATED_VIDEOS_BY_OWNERS -> RELATED_VIDEOS_BY_OWNERS;
            case EsTokEntityRelationRequest.RELATED_OWNERS_BY_OWNERS -> RELATED_OWNERS_BY_OWNERS;
            default -> RELATED_VIDEOS_BY_VIDEOS;
        };
    }

    record RelationProfile(
            float ownerCandidateBoost,
            double ownerScoreBoost,
            boolean targetIsVideos,
            double minimumCoverageStrict,
            double minimumCoverageRelaxed,
            int minimumOverlapStrict,
            int minimumOverlapRelaxed,
            int minimumSurfaceOverlapStrict,
            int minimumSurfaceOverlapRelaxed,
            boolean supportsRelaxedFallback,
            SupportProfile supportProfile,
            ScoreWeights scoreWeights,
            CoveragePenaltyProfile coveragePenaltyProfile,
            OwnerCandidateProfile ownerCandidateProfile,
            VideoRankingProfile videoRankingProfile,
            OwnerRankingProfile ownerRankingProfile) {

        boolean acceptSameOwnerCandidate(
                double overlapWeight,
                int overlapCount,
                int strongOverlapCount,
                double coverage) {
            if (!ownerCandidateProfile.requireSameOwnerOverlap()) {
                return true;
            }
            if (overlapWeight <= 0.0d || overlapCount <= 0) {
                return false;
            }
            return strongOverlapCount > 0 || coverage >= ownerCandidateProfile.minimumSameOwnerCoverage();
        }

        double minimumCoverage(boolean relaxedMode) {
            return relaxedMode ? minimumCoverageRelaxed : minimumCoverageStrict;
        }

        int minimumOverlapCount(boolean relaxedMode) {
            return relaxedMode ? minimumOverlapRelaxed : minimumOverlapStrict;
        }

        int minimumSurfaceOverlapCount(boolean relaxedMode) {
            return relaxedMode ? minimumSurfaceOverlapRelaxed : minimumSurfaceOverlapStrict;
        }

        boolean requiresStrongMatch(boolean relaxedMode) {
            return !relaxedMode && this == RELATED_VIDEOS_BY_VIDEOS || !relaxedMode && this == RELATED_OWNERS_BY_OWNERS;
        }

        boolean shouldPreferSupportedTokens(Map<String, Integer> tokenDocSupport) {
            if (supportProfile.minimumSupportedTokenCount() <= 1) {
                return false;
            }
            long supportedTokenCount = tokenDocSupport.values().stream()
                    .filter(count -> count != null && count >= 2)
                    .count();
            return supportedTokenCount >= supportProfile.minimumSupportedTokenCount();
        }

        double supportSignalBoost(int supportCount) {
            if (supportCount <= 1) {
                return supportProfile.singleDocMultiplier();
            }
            return 1.0d + Math.min(
                    supportProfile.maxExtraBoost(),
                    (supportCount - 1) * supportProfile.additionalSupportStep());
        }

        double score(
                double overlapWeight,
                double coverage,
                int strongOverlapCount,
                int overlapCount,
                int surfaceOverlapCount,
                double hitWeight,
                double recency,
                double quality,
                double influence,
                boolean sameOwner,
                boolean relaxedMode) {
            double score = (overlapWeight * scoreWeights.overlapWeight())
                    + (coverage * scoreWeights.coverageWeight())
                    + (strongOverlapCount * scoreWeights.strongOverlapWeight())
                    + (overlapCount * scoreWeights.overlapCountWeight())
                    + (surfaceOverlapCount * scoreWeights.surfaceOverlapWeight())
                    + (sameOwner ? ownerScoreBoost : 0.0d)
                    + (hitWeight * scoreWeights.hitWeight())
                    + (recency * scoreWeights.recencyWeight())
                    + (quality * scoreWeights.qualityWeight())
                    + (influence * scoreWeights.influenceWeight());
            if (!sameOwner && coverage < coveragePenaltyProfile.threshold(relaxedMode)) {
                score *= coveragePenaltyProfile.multiplier(relaxedMode);
            }
            return score;
        }

        boolean acceptOwnerCandidate(int docFreq, double score, boolean relaxedMode) {
            if (this != RELATED_OWNERS_BY_OWNERS) {
                return true;
            }
            if (relaxedMode) {
                return docFreq >= 1 && score >= ownerRankingProfile.relaxedMinimumScore();
            }
            return docFreq >= 2 || score >= ownerRankingProfile.strictMinimumScore();
        }

        double adjustedVideoScore(double score, int ownerCount) {
            return score / (1.0d + (ownerCount * videoRankingProfile.ownerPenalty()));
        }

        double videoAccumulatorScore(double bestScore, double score, int docFreq) {
            return bestScore
                    + Math.max(0.0d, score - bestScore) * videoRankingProfile.secondaryScoreWeight()
                    + (Math.log1p(docFreq) * videoRankingProfile.docFreqWeight());
        }

        double ownerAccumulatorScore(Iterable<Double> rankedContributions, int docFreq) {
            double total = 0.0d;
            double decay = 1.0d;
            for (double contribution : rankedContributions) {
                total += contribution * decay;
                decay *= ownerRankingProfile.contributionDecay();
            }
            return total + (Math.log1p(docFreq) * ownerRankingProfile.docFreqWeight());
        }
    }

    record SupportProfile(int minimumSupportedTokenCount, double additionalSupportStep, double maxExtraBoost, double singleDocMultiplier) {
    }

    record ScoreWeights(
            double overlapWeight,
            double coverageWeight,
            double strongOverlapWeight,
            double overlapCountWeight,
            double surfaceOverlapWeight,
            double hitWeight,
            double recencyWeight,
            double qualityWeight,
            double influenceWeight) {
    }

    record CoveragePenaltyProfile(double strictThreshold, double relaxedThreshold, double strictMultiplier, double relaxedMultiplier) {
        double threshold(boolean relaxedMode) {
            return relaxedMode ? relaxedThreshold : strictThreshold;
        }

        double multiplier(boolean relaxedMode) {
            return relaxedMode ? relaxedMultiplier : strictMultiplier;
        }
    }

    record OwnerCandidateProfile(boolean requireSameOwnerOverlap, double minimumSameOwnerCoverage) {
    }

    record VideoRankingProfile(double ownerPenalty, double secondaryScoreWeight, double docFreqWeight) {
    }

    record OwnerRankingProfile(double contributionDecay, double docFreqWeight, double strictMinimumScore, double relaxedMinimumScore) {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, RelationProfile> loadProfiles() {
        try (InputStream inputStream = RelationTuning.class.getResourceAsStream("/tuning/relation_tuning.json")) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing relation tuning resource");
            }
            Map<String, Object> root = new ObjectMapper().readValue(inputStream, Map.class);
            Map<String, Object> profiles = (Map<String, Object>) root.get("profiles");
            return Map.of(
                    EsTokEntityRelationRequest.RELATED_VIDEOS_BY_VIDEOS,
                    relationProfile((Map<String, Object>) profiles.get(EsTokEntityRelationRequest.RELATED_VIDEOS_BY_VIDEOS)),
                    EsTokEntityRelationRequest.RELATED_OWNERS_BY_VIDEOS,
                    relationProfile((Map<String, Object>) profiles.get(EsTokEntityRelationRequest.RELATED_OWNERS_BY_VIDEOS)),
                    EsTokEntityRelationRequest.RELATED_VIDEOS_BY_OWNERS,
                    relationProfile((Map<String, Object>) profiles.get(EsTokEntityRelationRequest.RELATED_VIDEOS_BY_OWNERS)),
                    EsTokEntityRelationRequest.RELATED_OWNERS_BY_OWNERS,
                    relationProfile((Map<String, Object>) profiles.get(EsTokEntityRelationRequest.RELATED_OWNERS_BY_OWNERS)));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load relation tuning resource", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static RelationProfile relationProfile(Map<String, Object> values) {
        Map<String, Object> support = (Map<String, Object>) values.get("support_profile");
        Map<String, Object> weights = (Map<String, Object>) values.get("score_weights");
        Map<String, Object> penalty = (Map<String, Object>) values.get("coverage_penalty_profile");
        Map<String, Object> ownerCandidate = (Map<String, Object>) values.get("owner_candidate_profile");
        Map<String, Object> videoRanking = (Map<String, Object>) values.get("video_ranking_profile");
        Map<String, Object> ownerRanking = (Map<String, Object>) values.get("owner_ranking_profile");
        return new RelationProfile(
                floatValue(values, "owner_candidate_boost", 0.0f),
                doubleValue(values, "owner_score_boost", 0.0d),
                booleanValue(values, "target_is_videos", false),
                doubleValue(values, "minimum_coverage_strict", 0.0d),
                doubleValue(values, "minimum_coverage_relaxed", 0.0d),
                intValue(values, "minimum_overlap_strict", 1),
                intValue(values, "minimum_overlap_relaxed", 1),
                intValue(values, "minimum_surface_overlap_strict", 0),
                intValue(values, "minimum_surface_overlap_relaxed", 0),
                booleanValue(values, "supports_relaxed_fallback", false),
                new SupportProfile(
                        intValue(support, "minimum_supported_token_count", 1),
                        doubleValue(support, "additional_support_step", 0.0d),
                        doubleValue(support, "max_extra_boost", 0.0d),
                        doubleValue(support, "single_doc_multiplier", 1.0d)),
                new ScoreWeights(
                        doubleValue(weights, "overlap_weight", 0.0d),
                        doubleValue(weights, "coverage_weight", 0.0d),
                        doubleValue(weights, "strong_overlap_weight", 0.0d),
                        doubleValue(weights, "overlap_count_weight", 0.0d),
                        doubleValue(weights, "surface_overlap_weight", 0.0d),
                        doubleValue(weights, "hit_weight", 0.0d),
                        doubleValue(weights, "recency_weight", 0.0d),
                        doubleValue(weights, "quality_weight", 0.0d),
                        doubleValue(weights, "influence_weight", 0.0d)),
                new CoveragePenaltyProfile(
                        doubleValue(penalty, "strict_threshold", 0.0d),
                        doubleValue(penalty, "relaxed_threshold", 0.0d),
                        doubleValue(penalty, "strict_multiplier", 1.0d),
                        doubleValue(penalty, "relaxed_multiplier", 1.0d)),
                new OwnerCandidateProfile(
                        booleanValue(ownerCandidate, "require_same_owner_overlap", false),
                        doubleValue(ownerCandidate, "minimum_same_owner_coverage", 0.0d)),
                new VideoRankingProfile(
                        doubleValue(videoRanking, "owner_penalty", 0.0d),
                        doubleValue(videoRanking, "secondary_score_weight", 0.0d),
                        doubleValue(videoRanking, "doc_freq_weight", 0.0d)),
                new OwnerRankingProfile(
                        doubleValue(ownerRanking, "contribution_decay", 0.0d),
                        doubleValue(ownerRanking, "doc_freq_weight", 0.0d),
                        doubleValue(ownerRanking, "strict_minimum_score", 0.0d),
                        doubleValue(ownerRanking, "relaxed_minimum_score", 0.0d)));
    }

    private static int intValue(Map<String, Object> values, String key, int defaultValue) {
        Object value = values == null ? null : values.get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static float floatValue(Map<String, Object> values, String key, float defaultValue) {
        Object value = values == null ? null : values.get(key);
        return value instanceof Number number ? number.floatValue() : defaultValue;
    }

    private static double doubleValue(Map<String, Object> values, String key, double defaultValue) {
        Object value = values == null ? null : values.get(key);
        return value instanceof Number number ? number.doubleValue() : defaultValue;
    }

    private static boolean booleanValue(Map<String, Object> values, String key, boolean defaultValue) {
        Object value = values == null ? null : values.get(key);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    private RelationTuning() {
    }
}