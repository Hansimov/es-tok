package org.es.tok.suggest;

import com.github.houbb.pinyin.constant.enums.PinyinStyleEnum;
import com.github.houbb.pinyin.util.PinyinHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PinyinSupport {

    private static final float MIN_CHINESE_ANCHOR_SCORE = 0.34f;

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
        if (candidateKey.full().startsWith(inputFull)) {
            float baseScore = 1.0f - lengthPenalty(candidateKey.full().length() - inputFull.length(), 0.04f, 0.32f);
            return applyChineseAnchor(baseScore, chineseAnchoredInput, chineseAnchorScore);
        }
        if (!chineseAnchoredInput && !inputInitials.isEmpty() && candidateKey.initials().startsWith(inputInitials)) {
            int extraInitials = Math.max(0, candidateKey.initials().length() - inputInitials.length());
            if (extraInitials == 0) {
                return 1.72f;
            }
            return 0.76f - lengthPenalty(extraInitials, 0.12f, 0.6f);
        }
        if (matchesSyllablePrefixes(inputFull, candidateKey.syllables())) {
            int extraSyllables = Math.max(0, candidateKey.syllables().size() - estimateConsumedSyllables(inputFull, candidateKey.syllables()));
            float baseScore = 0.88f - lengthPenalty(extraSyllables, 0.06f, 0.36f);
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
        if (inputKey.full().equals(candidateKey.full())) {
            return applyChineseAnchor(2.4f, chineseAnchoredInput, chineseAnchorScore);
        }
        if (!chineseAnchoredInput && !inputKey.initials().isEmpty() && inputKey.initials().equals(candidateKey.initials())) {
            return 1.45f;
        }
        if (!chineseAnchoredInput && candidateKey.initials().startsWith(inputKey.full())) {
            int extraInitials = Math.max(0, candidateKey.initials().length() - inputKey.full().length());
            if (extraInitials == 0) {
                return 1.08f;
            }
            return 0.72f - lengthPenalty(extraInitials, 0.12f, 0.48f);
        }
        if (candidateKey.full().startsWith(inputKey.full())) {
            return applyChineseAnchor(0.94f, chineseAnchoredInput, chineseAnchorScore);
        }
        if (matchesSyllablePrefixes(inputKey.full(), candidateKey.syllables())) {
            return applyChineseAnchor(0.9f, chineseAnchoredInput, chineseAnchorScore);
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

    private static boolean hasAsciiLettersOrDigits(String text) {
        return text.chars().anyMatch(ch -> ch < 128 && Character.isLetterOrDigit(ch));
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