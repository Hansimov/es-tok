package org.es.tok.relations;

import org.es.tok.action.EsTokEntityRelationRequest;

import java.util.Map;

final class RelationTuning {
    private static final RelationProfile RELATED_VIDEOS_BY_VIDEOS = new RelationProfile(
            1.5f,
            16.0d,
            true,
            0.12d,
            0.08d,
            2,
            1,
            0,
            0,
            true,
            new SupportProfile(1, 0.12d, 0.45d, 1.0d),
            new ScoreWeights(12.0d, 78.0d, 14.0d, 2.5d, 0.0d, 2.4d, 12.0d, 8.0d, 3.2d),
            new CoveragePenaltyProfile(0.18d, 0.12d, 0.62d, 0.78d),
            new OwnerCandidateProfile(false, 0.0d),
            new VideoRankingProfile(0.45d, 0.28d, 3.0d),
            new OwnerRankingProfile(0.68d, 4.0d, 320.0d, 240.0d));

    private static final RelationProfile RELATED_OWNERS_BY_VIDEOS = new RelationProfile(
            1.8f,
            22.0d,
            false,
            0.08d,
            0.05d,
            1,
            1,
            0,
            0,
            false,
            new SupportProfile(1, 0.12d, 0.45d, 1.0d),
            new ScoreWeights(12.0d, 78.0d, 14.0d, 2.5d, 0.0d, 2.4d, 12.0d, 8.0d, 3.2d),
            new CoveragePenaltyProfile(0.18d, 0.12d, 0.62d, 0.78d),
            new OwnerCandidateProfile(false, 0.0d),
            new VideoRankingProfile(0.45d, 0.28d, 3.0d),
            new OwnerRankingProfile(0.68d, 4.0d, 320.0d, 240.0d));

    private static final RelationProfile RELATED_VIDEOS_BY_OWNERS = new RelationProfile(
            2.2f,
            24.0d,
            true,
            0.10d,
            0.06d,
            2,
            1,
            1,
            0,
            true,
            new SupportProfile(3, 0.36d, 1.20d, 0.38d),
            new ScoreWeights(12.0d, 78.0d, 14.0d, 2.5d, 6.0d, 2.4d, 12.0d, 8.0d, 3.2d),
            new CoveragePenaltyProfile(0.22d, 0.14d, 0.48d, 0.70d),
            new OwnerCandidateProfile(true, 0.0d),
            new VideoRankingProfile(0.68d, 0.16d, 2.2d),
            new OwnerRankingProfile(0.68d, 4.0d, 320.0d, 240.0d));

    private static final RelationProfile RELATED_OWNERS_BY_OWNERS = new RelationProfile(
            0.0f,
            0.0d,
            false,
            0.20d,
            0.12d,
            2,
            1,
            1,
            0,
            true,
            new SupportProfile(3, 0.32d, 1.10d, 0.52d),
            new ScoreWeights(12.0d, 78.0d, 14.0d, 2.5d, 4.0d, 2.4d, 12.0d, 8.0d, 3.2d),
            new CoveragePenaltyProfile(0.18d, 0.12d, 0.62d, 0.78d),
            new OwnerCandidateProfile(false, 0.0d),
            new VideoRankingProfile(0.45d, 0.28d, 3.0d),
            new OwnerRankingProfile(0.68d, 4.0d, 320.0d, 240.0d));

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

    private RelationTuning() {
    }
}