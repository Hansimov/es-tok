package org.es.tok.text;

public final class TopicQualityHeuristics {
    private static final TextQualityRules RULES = TextQualityRules.DEFAULT;

    public static String sanitizeQueryText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replaceAll("https?://\\S+", " ")
                .replaceAll("www\\.\\S+", " ")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static boolean isUsefulOwnerQuerySeedTerm(String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        String normalized = TextNormalization.normalizeLower(term);
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.contains("://") || normalized.startsWith("www.")) {
            return false;
        }
        if (RULES.isNoisyAsciiTerm(normalized)) {
            return false;
        }
        int codePointLength = normalized.codePointCount(0, normalized.length());
        if (codePointLength < 2) {
            return false;
        }
        if (normalized.chars().allMatch(Character::isDigit)) {
            return false;
        }
        boolean containsHan = normalized.codePoints().anyMatch(TopicQualityHeuristics::isHanCodePoint);
        if (containsHan) {
            if (RULES.isNoisyCjkTerm(normalized)) {
                return false;
            }
            for (String fragment : RULES.noisyTermFragments()) {
                if (normalized.contains(fragment)) {
                    return false;
                }
            }
            return true;
        }
        boolean asciiAlphaNumeric = normalized.chars().allMatch(ch -> ch < 128 && Character.isLetterOrDigit(ch));
        if (!asciiAlphaNumeric) {
            return false;
        }
        boolean hasLetter = normalized.chars().anyMatch(Character::isLetter);
        return hasLetter && codePointLength >= 3 && codePointLength <= 24;
    }

    public static boolean isStrongOwnerQuerySeedTerm(String term) {
        if (!isUsefulOwnerQuerySeedTerm(term)) {
            return false;
        }
        int hanCount = 0;
        int asciiLetterCount = 0;
        for (int index = 0; index < term.length(); index++) {
            char current = term.charAt(index);
            if (isHanCodePoint(current)) {
                hanCount++;
            }
            if (current < 128 && Character.isLetter(current)) {
                asciiLetterCount++;
            }
        }
        return hanCount >= 2 || asciiLetterCount >= 4 || term.codePointCount(0, term.length()) >= 4;
    }

    public static int ownerQuerySeedPriority(String term) {
        if (!isUsefulOwnerQuerySeedTerm(term)) {
            return Integer.MIN_VALUE;
        }
        int priority = term.codePointCount(0, term.length());
        if (isStrongOwnerQuerySeedTerm(term)) {
            priority += 6;
        }
        long digitCount = term.chars().filter(Character::isDigit).count();
        if (digitCount > 0 && digitCount < term.codePointCount(0, term.length())) {
            priority += 3;
        }
        for (String noisyTerm : RULES.noisyCjkTerms()) {
            if (term.equals(noisyTerm)) {
                priority -= 8;
            }
        }
        for (String fragment : RULES.noisyTermFragments()) {
            if (term.contains(fragment)) {
                priority -= 12;
            }
        }
        return priority;
    }

    public static boolean isUsefulAssociateSeedTerm(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (containsChinese(token)) {
            return true;
        }
        int codePointLength = token.codePointCount(0, token.length());
        boolean asciiAlphaNum = token.chars().allMatch(ch -> ch < 128 && Character.isLetterOrDigit(ch));
        if (asciiAlphaNum) {
            return codePointLength >= 3;
        }
        return codePointLength >= 2;
    }

    public static boolean isCandidateRelationToken(String token) {
        if (token == null || token.isBlank() || token.length() < 2) {
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

    public static boolean isStrongRelationToken(String token) {
        if (!isCandidateRelationToken(token)) {
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

    public static boolean containsChinese(String token) {
        return token != null && token.codePoints().anyMatch(TopicQualityHeuristics::isHanCodePoint);
    }

    public static boolean isFunctionWord(int codePoint) {
        return RULES.isFunctionWord(codePoint);
    }

    private static boolean isHanCodePoint(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private TopicQualityHeuristics() {
    }
}