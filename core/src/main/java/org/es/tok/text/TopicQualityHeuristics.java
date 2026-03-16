package org.es.tok.text;

import java.util.LinkedHashSet;
import java.util.Set;

public final class TopicQualityHeuristics {
    private static final TextQualityRules RULES = TextQualityRules.DEFAULT;

    public static String sanitizeQueryText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String sanitized = text
                .replaceAll("\\p{Cf}+", " ")
                .replaceAll("https?://\\S+", " ")
                .replaceAll("www\\.\\S+", " ")
                .replaceAll("[\\r\\n\\t]+", " ");
        for (String fragment : RULES.noisyTermFragments()) {
            if (!fragment.isBlank()) {
                sanitized = sanitized.replace(fragment, " ");
            }
        }
        return sanitized
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static LinkedHashSet<String> filterOwnerSeedTerms(Iterable<String> terms) {
        return filterTerms(terms, RULES.ownerSeedTermsProfile(), TopicQualityHeuristics::isUsefulOwnerQuerySeedTerm);
    }

    public static LinkedHashSet<String> filterAssociateSeedTerms(Iterable<String> terms) {
        return filterTerms(terms, RULES.associateSeedTermsProfile(), TopicQualityHeuristics::isUsefulAssociateSeedTerm);
    }

    public static LinkedHashSet<String> filterAssociateCandidateTerms(Iterable<String> terms) {
        return filterTerms(terms, RULES.associateCandidatesProfile(), term -> term != null && !term.isBlank());
    }

    public static boolean isOwnerSeedTermContextuallyAllowed(String term, Iterable<String> contextTerms) {
        return !matchesContextualExclusion(term, copyTerms(contextTerms), RULES.ownerSeedTermsProfile());
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

    public static boolean hasLeadingOrTrailingFunctionWord(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int first = text.codePointAt(0);
        int last = text.codePointBefore(text.length());
        return isFunctionWord(first) || isFunctionWord(last);
    }

    public static boolean isFunctionWordHeavy(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int codePointLength = text.codePointCount(0, text.length());
        if (codePointLength > 3) {
            return false;
        }
        int matched = 0;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isFunctionWord(codePoint)) {
                matched++;
            }
        }
        return matched >= Math.max(1, codePointLength - 1);
    }

    private static LinkedHashSet<String> filterTerms(
            Iterable<String> terms,
            TextQualityRules.ContextRuleProfile profile,
            java.util.function.Predicate<String> baseAcceptance) {
        LinkedHashSet<String> normalized = copyTerms(terms);
        if (normalized.isEmpty()) {
            return normalized;
        }
        LinkedHashSet<String> filtered = new LinkedHashSet<>();
        for (String term : normalized) {
            if (!baseAcceptance.test(term)) {
                continue;
            }
            if (matchesContextualExclusion(term, normalized, profile)) {
                continue;
            }
            filtered.add(term);
        }
        return filtered;
    }

    private static boolean matchesContextualExclusion(
            String term,
            Set<String> contextTerms,
            TextQualityRules.ContextRuleProfile profile) {
        if (term == null || term.isBlank()) {
            return true;
        }
        if (profile.excludeExactTerms().contains(term)) {
            return true;
        }
        for (String fragment : profile.excludeContainsTerms()) {
            if (term.contains(fragment)) {
                return true;
            }
        }
        if (matchesAffixDeclusion(term, contextTerms, profile.decludePrefixes(), true)) {
            return true;
        }
        return matchesAffixDeclusion(term, contextTerms, profile.decludeSuffixes(), false);
    }

    private static boolean matchesAffixDeclusion(String term, Set<String> contextTerms, Set<String> affixes, boolean prefix) {
        for (String affix : affixes) {
            if (term.length() <= affix.length()) {
                continue;
            }
            if (prefix && term.startsWith(affix)) {
                String baseForm = term.substring(affix.length());
                if (contextTerms.contains(baseForm)) {
                    return true;
                }
            }
            if (!prefix && term.endsWith(affix)) {
                String baseForm = term.substring(0, term.length() - affix.length());
                if (contextTerms.contains(baseForm)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static LinkedHashSet<String> copyTerms(Iterable<String> terms) {
        LinkedHashSet<String> copied = new LinkedHashSet<>();
        if (terms == null) {
            return copied;
        }
        for (String term : terms) {
            if (term != null && !term.isBlank()) {
                copied.add(term);
            }
        }
        return copied;
    }

    private static boolean isHanCodePoint(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private TopicQualityHeuristics() {
    }
}