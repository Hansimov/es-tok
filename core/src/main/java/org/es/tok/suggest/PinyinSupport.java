package org.es.tok.suggest;

import com.github.houbb.pinyin.constant.enums.PinyinStyleEnum;
import com.github.houbb.pinyin.util.PinyinHelper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PinyinSupport {

    private static final float MIN_CHINESE_ANCHOR_SCORE = 0.34f;
    public static final String PRECOMPUTED_FULL_PREFIX = "__pyf__";
    public static final String PRECOMPUTED_INITIALS_PREFIX = "__pyi__";
    public static final String PRECOMPUTED_SEPARATOR = "|";

    private PinyinSupport() {
    }

    static PinyinKey pinyinKey(String text) {
        if (text == null || text.isBlank()) {
            return PinyinKey.EMPTY;
        }

        String normalized = normalizeInput(text);
        String raw = PinyinHelper.toPinyin(text, PinyinStyleEnum.NORMAL);
        String rawInitials = PinyinHelper.toPinyin(text, PinyinStyleEnum.FIRST_LETTER);
        List<String> syllables = tokenizeSyllables(raw);
        String full = joinSyllables(syllables);
        String initials = normalizeInput(rawInitials);
        if (full.isEmpty()) {
            full = normalized;
        }
        if (full.isEmpty() && initials.isEmpty()) {
            return PinyinKey.EMPTY;
        }
        if (initials.isEmpty() && !syllables.isEmpty()) {
            StringBuilder builder = new StringBuilder(syllables.size());
            for (String syllable : syllables) {
                builder.append(syllable.charAt(0));
            }
            initials = builder.toString();
        }
        return new PinyinKey(full, initials, syllables);
    }

    static boolean shouldUsePinyin(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return containsChinese(text) || hasAsciiLettersOrDigits(text);
    }

    static boolean shouldIndexTerm(String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        if (!containsChinese(term)) {
            return false;
        }
        return term.codePointCount(0, term.length()) <= 12;
    }

    static boolean shouldUseInitialsBuckets(String input) {
        return containsChinese(input) == false;
    }

    static boolean isPureChineseQuery(String text) {
        return containsChinese(text) && hasAsciiLettersOrDigits(text) == false;
    }

    static boolean isAsciiAlphaNumericQuery(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        boolean sawAlphaNumeric = false;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            if (codePoint >= 128 || Character.isLetterOrDigit(codePoint) == false) {
                return false;
            }
            sawAlphaNumeric = true;
        }
        return sawAlphaNumeric;
    }

    static boolean matchesChineseAnchor(String input, String candidate) {
        return chineseAnchorScore(input, candidate) >= MIN_CHINESE_ANCHOR_SCORE;
    }

    static float prefixMatchScore(String input, String candidate, PinyinKey candidateKey) {
        PinyinKey inputKey = pinyinKey(input);
        if (inputKey.isEmpty() || candidateKey.isEmpty()) {
            return 0.0f;
        }

        String inputFull = inputKey.full();
        String inputInitials = inputKey.initials();
        float chineseAnchorScore = chineseAnchorScore(input, candidate);
        boolean chineseAnchoredInput = containsChinese(input);
        boolean pureChineseInput = isPureChineseQuery(input);
        boolean literalChinesePrefix = pureChineseInput && literalPrefixMatch(input, candidate);
        boolean asciiLiteralQuery = isAsciiAlphaNumericQuery(input);
        boolean literalPrefix = literalPrefixMatch(input, candidate);
        boolean hasDigits = normalizeInput(input).chars().anyMatch(Character::isDigit);
        if (pureChineseInput && !literalChinesePrefix) {
            return 0.0f;
        }
        if (candidateKey.full().startsWith(inputFull)) {
            float baseScore = 1.0f - lengthPenalty(candidateKey.full().length() - inputFull.length(), 0.04f, 0.32f);
            if (asciiLiteralQuery && !literalPrefix) {
                baseScore *= asciiNonLiteralPinyinPenalty(inputFull.length(), hasDigits, false);
            }
            baseScore = applyLiteralChinesePrefixBias(baseScore, pureChineseInput, literalChinesePrefix);
            return applyChineseAnchor(baseScore, chineseAnchoredInput, chineseAnchorScore);
        }
        if (!chineseAnchoredInput && !inputInitials.isEmpty() && candidateKey.initials().startsWith(inputInitials)) {
            int extraInitials = Math.max(0, candidateKey.initials().length() - inputInitials.length());
            float baseScore;
            if (extraInitials == 0) {
                baseScore = 1.72f;
            } else {
                baseScore = 0.76f - lengthPenalty(extraInitials, 0.12f, 0.6f);
            }
            if (asciiLiteralQuery && !literalPrefix) {
                baseScore *= asciiNonLiteralPinyinPenalty(inputInitials.length(), hasDigits, true);
            }
            return baseScore;
        }
        if (matchesSyllablePrefixes(inputFull, candidateKey.syllables())) {
            int extraSyllables = Math.max(0, candidateKey.syllables().size() - estimateConsumedSyllables(inputFull, candidateKey.syllables()));
            float baseScore = 0.88f - lengthPenalty(extraSyllables, 0.06f, 0.36f);
            if (asciiLiteralQuery && !literalPrefix) {
                baseScore *= asciiNonLiteralPinyinPenalty(inputFull.length(), hasDigits, false);
            }
            baseScore = applyLiteralChinesePrefixBias(baseScore, pureChineseInput, literalChinesePrefix);
            return applyChineseAnchor(baseScore, chineseAnchoredInput, chineseAnchorScore);
        }
        return 0.0f;
    }

    public static boolean isPinyinLikeQuery(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        PinyinKey key = pinyinKey(text);
        if (key.isEmpty()) {
            return false;
        }
        String normalizedInput = normalizeInput(text);
        if (!normalizedInput.isEmpty()) {
            if (key.full().equals(normalizedInput) || key.initials().equals(normalizedInput)) {
                return true;
            }
            if (matchesSyllablePrefixes(normalizedInput, key.syllables())) {
                return true;
            }
        }
        return containsChinese(text) && text.chars().anyMatch(ch -> ch < 128 && Character.isLetter(ch));
    }

    static float correctionMatchScore(String input, String candidate) {
        PinyinKey inputKey = pinyinKey(input);
        PinyinKey candidateKey = pinyinKey(candidate);
        if (inputKey.isEmpty() || candidateKey.isEmpty()) {
            return 0.0f;
        }

        boolean chineseAnchoredInput = containsChinese(input);
        float chineseAnchorScore = chineseAnchorScore(input, candidate);
        boolean asciiLiteralQuery = isAsciiAlphaNumericQuery(input);
        boolean literalPrefix = literalPrefixMatch(input, candidate);
        boolean hasDigits = normalizeInput(input).chars().anyMatch(Character::isDigit);
        if (inputKey.full().equals(candidateKey.full())) {
            float score = 2.4f;
            if (asciiLiteralQuery && !literalPrefix) {
                score *= asciiNonLiteralPinyinPenalty(inputKey.full().length(), hasDigits, false);
            }
            return applyChineseAnchor(score, chineseAnchoredInput, chineseAnchorScore);
        }
        if (!chineseAnchoredInput && !inputKey.initials().isEmpty() && inputKey.initials().equals(candidateKey.initials())) {
            float score = 1.45f;
            if (asciiLiteralQuery && !literalPrefix) {
                score *= asciiNonLiteralPinyinPenalty(inputKey.initials().length(), hasDigits, true);
            }
            return score;
        }
        if (!chineseAnchoredInput && candidateKey.initials().startsWith(inputKey.full())) {
            int extraInitials = Math.max(0, candidateKey.initials().length() - inputKey.full().length());
            float score;
            if (extraInitials == 0) {
                score = 1.08f;
            } else {
                score = 0.72f - lengthPenalty(extraInitials, 0.12f, 0.48f);
            }
            if (asciiLiteralQuery && !literalPrefix) {
                score *= asciiNonLiteralPinyinPenalty(inputKey.full().length(), hasDigits, true);
            }
            return score;
        }
        if (candidateKey.full().startsWith(inputKey.full())) {
            float score = 0.94f;
            if (asciiLiteralQuery && !literalPrefix) {
                score *= asciiNonLiteralPinyinPenalty(inputKey.full().length(), hasDigits, false);
            }
            return applyChineseAnchor(score, chineseAnchoredInput, chineseAnchorScore);
        }
        if (matchesSyllablePrefixes(inputKey.full(), candidateKey.syllables())) {
            float score = 0.9f;
            if (asciiLiteralQuery && !literalPrefix) {
                score *= asciiNonLiteralPinyinPenalty(inputKey.full().length(), hasDigits, false);
            }
            return applyChineseAnchor(score, chineseAnchoredInput, chineseAnchorScore);
        }
        return 0.0f;
    }

    static String normalizeInput(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        StringBuilder builder = new StringBuilder(text.length());
        String lower = text.toLowerCase(Locale.ROOT);
        for (int index = 0; index < lower.length(); ) {
            int codePoint = lower.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint < 128 && Character.isLetterOrDigit(codePoint)) {
                builder.appendCodePoint(codePoint);
            }
        }
        return builder.toString();
    }

    public static boolean containsChinese(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    public static List<String> precomputedSuggestionTerms(String text) {
        String normalized = normalizeLiteralText(text);
        if (!shouldIndexTerm(normalized)) {
            return List.of();
        }

        PinyinKey key = pinyinKey(normalized);
        if (key.isEmpty()) {
            return List.of();
        }

        Set<String> encoded = new LinkedHashSet<>();
        addPrecomputedTerm(encoded, PRECOMPUTED_FULL_PREFIX, precomputedMarkerKey(key, true), normalized);
        addPrecomputedTerm(encoded, PRECOMPUTED_INITIALS_PREFIX, precomputedMarkerKey(key, false), normalized);
        return List.copyOf(encoded);
    }

    public static String precomputedPrefix(boolean fullPinyin, String queryKey) {
        if (queryKey == null || queryKey.isBlank()) {
            return "";
        }
        int anchorLength = Math.min(4, queryKey.length());
        return (fullPinyin ? PRECOMPUTED_FULL_PREFIX : PRECOMPUTED_INITIALS_PREFIX)
                + queryKey.substring(0, anchorLength)
                + PRECOMPUTED_SEPARATOR;
    }

    public static boolean isPrecomputedSuggestionTerm(String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        return term.startsWith(PRECOMPUTED_FULL_PREFIX) || term.startsWith(PRECOMPUTED_INITIALS_PREFIX);
    }

    public static String decodePrecomputedSuggestionSurface(String term) {
        if (!isPrecomputedSuggestionTerm(term)) {
            return "";
        }
        int separatorIndex = term.indexOf(PRECOMPUTED_SEPARATOR);
        if (separatorIndex < 0 || separatorIndex + PRECOMPUTED_SEPARATOR.length() >= term.length()) {
            return "";
        }
        return term.substring(separatorIndex + PRECOMPUTED_SEPARATOR.length());
    }

    private static boolean hasAsciiLettersOrDigits(String text) {
        return text.chars().anyMatch(ch -> ch < 128 && Character.isLetterOrDigit(ch));
    }

    private static String precomputedMarkerKey(PinyinKey key, boolean fullPinyin) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        String rawKey = fullPinyin ? key.full() : key.initials();
        if (rawKey == null || rawKey.isBlank()) {
            return "";
        }
        int anchorLength = Math.min(4, rawKey.length());
        return rawKey.substring(0, anchorLength);
    }

    private static void addPrecomputedTerm(Set<String> encoded, String prefix, String key, String surface) {
        if (key == null || key.isBlank()) {
            return;
        }
        encoded.add(prefix + key + PRECOMPUTED_SEPARATOR + surface);
    }

    private static float asciiNonLiteralPinyinPenalty(int queryLength, boolean hasDigits, boolean initialsOnly) {
        float penalty;
        if (queryLength <= 3) {
            penalty = initialsOnly ? 0.01f : 0.03f;
        } else if (queryLength <= 5) {
            penalty = initialsOnly ? 0.02f : 0.06f;
        } else if (queryLength <= 8) {
            penalty = initialsOnly ? 0.03f : 0.1f;
        } else {
            penalty = initialsOnly ? 0.05f : 0.16f;
        }
        if (hasDigits) {
            penalty *= 0.9f;
        }
        return penalty;
    }

    private static float applyChineseAnchor(float baseScore, boolean chineseAnchoredInput, float chineseAnchorScore) {
        if (!chineseAnchoredInput) {
            return baseScore;
        }
        if (chineseAnchorScore < MIN_CHINESE_ANCHOR_SCORE) {
            return 0.0f;
        }
        return baseScore * (1.0f + (chineseAnchorScore * 0.75f));
    }

    private static float applyLiteralChinesePrefixBias(float baseScore, boolean pureChineseInput, boolean literalChinesePrefix) {
        if (!pureChineseInput) {
            return baseScore;
        }
        if (literalChinesePrefix) {
            return baseScore + 2.4f;
        }
        return 0.0f;
    }

    private static boolean literalPrefixMatch(String input, String candidate) {
        String normalizedInput = normalizeLiteralText(input);
        String normalizedCandidate = normalizeLiteralText(candidate);
        return !normalizedInput.isEmpty() && normalizedCandidate.startsWith(normalizedInput);
    }

    private static String normalizeLiteralText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (!Character.isWhitespace(codePoint)) {
                builder.appendCodePoint(codePoint);
            }
        }
        return builder.toString();
    }

    static float chineseAnchorScore(String input, String candidate) {
        String inputChinese = chineseOnly(input);
        if (inputChinese.isEmpty()) {
            return 1.0f;
        }

        String candidateChinese = chineseOnly(candidate);
        if (candidateChinese.isEmpty()) {
            return 0.0f;
        }

        int sharedPositions = sharedChinesePositions(inputChinese, candidateChinese);
        if (sharedPositions == 0) {
            return 0.0f;
        }

        int sharedLeading = sharedLeadingCodePoints(inputChinese, candidateChinese);
        int inputLength = inputChinese.codePointCount(0, inputChinese.length());
        int candidateLength = candidateChinese.codePointCount(0, candidateChinese.length());
        if (inputLength >= 3 && sharedPositions < 2) {
            return 0.0f;
        }
        if (inputLength == 2 && sharedPositions < 1) {
            return 0.0f;
        }
        float positionRatio = sharedPositions / (float) inputLength;
        float leadingRatio = sharedLeading / (float) inputLength;
        float lengthPenalty = Math.min(0.3f, Math.abs(candidateLength - inputLength) * 0.15f);
        float trailingBonus = hasSameTrailingCodePoint(inputChinese, candidateChinese) ? 0.2f : 0.0f;
        return Math.max(0.0f, positionRatio + (leadingRatio * 0.35f) + trailingBonus - lengthPenalty);
    }

    private static String chineseOnly(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                builder.appendCodePoint(codePoint);
            }
        }
        return builder.toString();
    }

    private static int sharedChinesePositions(String left, String right) {
        int[] leftCodePoints = left.codePoints().toArray();
        int[] rightCodePoints = right.codePoints().toArray();
        int max = Math.min(leftCodePoints.length, rightCodePoints.length);
        int matched = 0;
        for (int index = 0; index < max; index++) {
            if (leftCodePoints[index] == rightCodePoints[index]) {
                matched++;
            }
        }
        return matched;
    }

    private static int sharedLeadingCodePoints(String left, String right) {
        int[] leftCodePoints = left.codePoints().toArray();
        int[] rightCodePoints = right.codePoints().toArray();
        int max = Math.min(leftCodePoints.length, rightCodePoints.length);
        int matched = 0;
        while (matched < max && leftCodePoints[matched] == rightCodePoints[matched]) {
            matched++;
        }
        return matched;
    }

    private static boolean hasSameTrailingCodePoint(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return left.codePointBefore(left.length()) == right.codePointBefore(right.length());
    }

    private static List<String> tokenizeSyllables(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> syllables = new ArrayList<>();
        for (String part : text.split("\\s+")) {
            String normalized = normalizeInput(part);
            if (!normalized.isEmpty()) {
                syllables.add(normalized);
            }
        }
        return syllables;
    }

    private static String joinSyllables(List<String> syllables) {
        if (syllables.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String syllable : syllables) {
            builder.append(syllable);
        }
        return builder.toString();
    }

    static List<String> bucketKeys(PinyinKey key, boolean fullPinyin) {
        if (key == null || key.isEmpty()) {
            return List.of();
        }

        if (!fullPinyin) {
            return simplePrefixes(key.initials());
        }

        Set<String> keys = new LinkedHashSet<>();
        addSyllableBucketKeys(key.syllables(), 0, new StringBuilder(), keys);
        if (keys.isEmpty()) {
            keys.addAll(simplePrefixes(key.full()));
        }
        return List.copyOf(keys);
    }

    private static List<String> simplePrefixes(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> prefixes = new ArrayList<>();
        int maxPrefixLength = Math.min(4, text.length());
        for (int length = 1; length <= maxPrefixLength; length++) {
            prefixes.add(text.substring(0, length));
        }
        return prefixes;
    }

    private static void addSyllableBucketKeys(
            List<String> syllables,
            int syllableOffset,
            StringBuilder prefix,
            Set<String> keys) {
        if (syllableOffset >= syllables.size() || prefix.length() >= 4) {
            return;
        }

        String syllable = syllables.get(syllableOffset);
        int remaining = 4 - prefix.length();
        int maxTake = Math.min(remaining, syllable.length());
        int originalLength = prefix.length();
        for (int take = 1; take <= maxTake; take++) {
            prefix.append(syllable, 0, take);
            keys.add(prefix.toString());
            addSyllableBucketKeys(syllables, syllableOffset + 1, prefix, keys);
            prefix.setLength(originalLength);
        }
    }

    private static boolean matchesSyllablePrefixes(String input, List<String> syllables) {
        if (input.isEmpty() || syllables.isEmpty()) {
            return false;
        }
        return matchesSyllablePrefixes(input, 0, syllables, 0);
    }

    private static boolean matchesSyllablePrefixes(String input, int inputOffset, List<String> syllables, int syllableOffset) {
        if (inputOffset == input.length()) {
            return true;
        }
        if (syllableOffset >= syllables.size()) {
            return false;
        }

        String syllable = syllables.get(syllableOffset);
        int remaining = input.length() - inputOffset;
        int max = Math.min(remaining, syllable.length());
        for (int consumed = max; consumed >= 1; consumed--) {
            if (syllable.startsWith(input.substring(inputOffset, inputOffset + consumed))
                    && matchesSyllablePrefixes(input, inputOffset + consumed, syllables, syllableOffset + 1)) {
                return true;
            }
        }
        return false;
    }

    private static int estimateConsumedSyllables(String input, List<String> syllables) {
        int consumed = 0;
        int offset = 0;
        for (String syllable : syllables) {
            if (offset >= input.length()) {
                break;
            }
            int remaining = input.length() - offset;
            int max = Math.min(remaining, syllable.length());
            int matched = 0;
            for (int length = max; length >= 1; length--) {
                if (syllable.startsWith(input.substring(offset, offset + length))) {
                    matched = length;
                    break;
                }
            }
            if (matched == 0) {
                break;
            }
            consumed++;
            offset += matched;
        }
        return consumed;
    }

    private static float lengthPenalty(int extraLength, float step, float maxPenalty) {
        if (extraLength <= 0) {
            return 0.0f;
        }
        return Math.min(maxPenalty, extraLength * step);
    }

    record PinyinKey(String full, String initials, List<String> syllables) {
        private static final PinyinKey EMPTY = new PinyinKey("", "", List.of());

        boolean isEmpty() {
            return full.isEmpty() && initials.isEmpty();
        }
    }
}