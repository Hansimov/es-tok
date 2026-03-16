package org.es.tok.suggest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

final class RelatedOwnerQueryTuning {
    static final float EXPANSION_TERM_BOOST = 0.32f;
    static final int MAX_EXPANSION_TERMS = 3;
    private static final int MAX_SHORT_QUERY_TERMS = 8;
    private static final int MAX_MEDIUM_QUERY_TERMS = 6;
    private static final int MAX_LONG_QUERY_TERMS = 5;
    static final int MAX_EXPANSION_SCAN_LIMIT = 48;
    private static final double TOPIC_WEIGHT_MULTIPLIER = 1.35d;
    private static final double QUALITY_MULTIPLIER = 3.6d;
    private static final double INFLUENCE_MULTIPLIER = 3.1d;
    private static final double RECENCY_HALFLIFE_DAYS = 21.0d;
    private static final double FRESHNESS_MULTIPLIER = 1.4d;
    private static final List<String> NOISY_ASCII_TERMS = List.of(
            "http",
            "https",
            "www",
            "com",
            "html",
            "htm",
            "bilibi",
            "bilibili");

    static String sanitizeQueryText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String sanitized = text
                .replaceAll("https?://\\S+", " ")
                .replaceAll("www\\.\\S+", " ")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return sanitized;
    }

    static boolean isUsefulSeedTerm(String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        String normalized = term.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.contains("://") || normalized.startsWith("www.")) {
            return false;
        }
        if (NOISY_ASCII_TERMS.contains(normalized)) {
            return false;
        }
        int codePointLength = normalized.codePointCount(0, normalized.length());
        if (codePointLength < 2) {
            return false;
        }
        if (normalized.chars().allMatch(Character::isDigit)) {
            return false;
        }
        boolean containsHan = normalized.codePoints().anyMatch(RelatedOwnerQueryTuning::isHanCodePoint);
        if (containsHan) {
            return codePointLength >= 2;
        }
        boolean asciiAlphaNumeric = normalized.chars().allMatch(ch -> ch < 128 && Character.isLetterOrDigit(ch));
        if (!asciiAlphaNumeric) {
            return false;
        }
        boolean hasLetter = normalized.chars().anyMatch(Character::isLetter);
        if (!hasLetter) {
            return false;
        }
        return codePointLength >= 3 && codePointLength <= 24;
    }

    static int maxQueryTerms(String text, int availableTermCount) {
        if (availableTermCount <= 0) {
            return 0;
        }
        int codePointLength = text == null ? 0 : text.codePointCount(0, text.length());
        boolean hasWhitespace = text != null && text.chars().anyMatch(Character::isWhitespace);
        if (hasWhitespace || codePointLength >= 18) {
            return Math.min(MAX_LONG_QUERY_TERMS, availableTermCount);
        }
        if (codePointLength >= 10) {
            return Math.min(MAX_MEDIUM_QUERY_TERMS, availableTermCount);
        }
        return Math.min(MAX_SHORT_QUERY_TERMS, availableTermCount);
    }

    static List<QueryPlan> buildQueryPlans(String text, int selectedTermCount) {
        int primaryMinimumMatches = minimumSeedMatches(text, selectedTermCount);
        List<QueryPlan> plans = new ArrayList<>();
        plans.add(new QueryPlan(primaryMinimumMatches));
        if (primaryMinimumMatches > 1) {
            plans.add(new QueryPlan(Math.max(1, primaryMinimumMatches - 1)));
        }
        return plans;
    }

    static boolean shouldExpandTopicTerms(String text, int selectedTermCount) {
        if (selectedTermCount <= 0) {
            return false;
        }
        if (selectedTermCount >= 4) {
            return false;
        }
        int codePointLength = text == null ? 0 : text.codePointCount(0, text.length());
        boolean hasWhitespace = text != null && text.chars().anyMatch(Character::isWhitespace);
        return !hasWhitespace && codePointLength < 18;
    }

    static int candidateDocLimit(int size, int scanLimit, int selectedTermCount, int minimumSeedMatches) {
        int floor = minimumSeedMatches <= 1 ? 64 : 40;
        if (selectedTermCount >= 5) {
            floor = Math.max(floor, 56);
        }
        int target = Math.max(size * 10, floor);
        return Math.min(Math.max(size, scanLimit), target);
    }

    static float seedTermBoost(String seedTerm, int rank) {
        int codePointLength = seedTerm.codePointCount(0, seedTerm.length());
        double lengthBoost = 1.0d + (Math.min(5, codePointLength) * 0.12d);
        double rankDecay = Math.max(0.72d, 1.0d - (rank * 0.06d));
        return (float) (lengthBoost * rankDecay);
    }

    static SourceBackedRelatedOwnersService.RelatedOwnerDocSignals docSignals(
            long nowEpochSeconds,
            float hitScore,
            int rank,
            double statScore,
            long viewCount,
            long insertAt) {
        double topicWeight = ((Math.log1p(Math.max(1.0f, hitScore)) + 1.0d) * TOPIC_WEIGHT_MULTIPLIER)
                / (1.0d + (rank * 0.05d));
        double normalizedStatScore = Math.max(0.0d, statScore);
        double quality = Math.log1p(normalizedStatScore * 2400.0d) * QUALITY_MULTIPLIER;
        double influence = Math.log1p(Math.max(0L, viewCount)) * INFLUENCE_MULTIPLIER;
        double ageDays = ageDays(nowEpochSeconds, insertAt);
        double recencyFactor = 1.0d / (1.0d + (ageDays / RECENCY_HALFLIFE_DAYS));
        double freshness = recencyFactor * FRESHNESS_MULTIPLIER;
        double representativeSignal = topicWeight * ((quality * 1.05d) + (influence * 1.35d) + freshness + 1.0d);
        double rankingWeight = representativeSignal + (topicWeight * 3.0d) + (quality * 0.25d) + (influence * 0.2d);
        return new SourceBackedRelatedOwnersService.RelatedOwnerDocSignals(
                topicWeight,
                rankingWeight,
                representativeSignal,
                quality,
                influence);
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
            return hasWhitespace || codePointLength >= 12 ? 2 : 3;
        }
        if (hasWhitespace || codePointLength >= 18) {
            return Math.min(3, selectedTermCount);
        }
        return Math.min(4, Math.max(2, (int) Math.ceil(selectedTermCount * 0.6d)));
    }

    private static double ageDays(long nowEpochSeconds, long insertAt) {
        if (insertAt <= 0L || nowEpochSeconds <= 0L) {
            return 3650.0d;
        }
        return Math.max(0.0d, (nowEpochSeconds - insertAt) / 86400.0d);
    }

    private static boolean isHanCodePoint(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    record QueryPlan(int minimumSeedMatches) {
    }

    private RelatedOwnerQueryTuning() {
    }
}