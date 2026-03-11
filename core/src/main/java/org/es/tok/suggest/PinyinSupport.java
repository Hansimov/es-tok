package org.es.tok.suggest;

import com.github.houbb.pinyin.constant.enums.PinyinStyleEnum;
import com.github.houbb.pinyin.util.PinyinHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PinyinSupport {

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
        return containsChinese(text) || text.chars().anyMatch(ch -> ch < 128 && Character.isLetter(ch));
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

    static float prefixMatchScore(String input, PinyinKey candidateKey) {
        PinyinKey inputKey = pinyinKey(input);
        if (inputKey.isEmpty() || candidateKey.isEmpty()) {
            return 0.0f;
        }

        String inputFull = inputKey.full();
        String inputInitials = inputKey.initials();
        if (candidateKey.full().startsWith(inputFull)) {
            return 1.0f - lengthPenalty(candidateKey.full().length() - inputFull.length(), 0.04f, 0.32f);
        }
        if (!inputInitials.isEmpty() && candidateKey.initials().startsWith(inputInitials)) {
            int extraInitials = Math.max(0, candidateKey.initials().length() - inputInitials.length());
            if (extraInitials == 0) {
                return 1.72f;
            }
            return 0.76f - lengthPenalty(extraInitials, 0.12f, 0.6f);
        }
        if (matchesSyllablePrefixes(inputFull, candidateKey.syllables())) {
            int extraSyllables = Math.max(0, candidateKey.syllables().size() - estimateConsumedSyllables(inputFull, candidateKey.syllables()));
            return 0.88f - lengthPenalty(extraSyllables, 0.06f, 0.36f);
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

        if (inputKey.full().equals(candidateKey.full())) {
            return 1.2f;
        }
        if (!inputKey.initials().isEmpty() && inputKey.initials().equals(candidateKey.initials())) {
            return 1.02f;
        }
        if (candidateKey.initials().startsWith(inputKey.full())) {
            int extraInitials = Math.max(0, candidateKey.initials().length() - inputKey.full().length());
            if (extraInitials == 0) {
                return 1.08f;
            }
            return 0.72f - lengthPenalty(extraInitials, 0.12f, 0.48f);
        }
        if (candidateKey.full().startsWith(inputKey.full())) {
            return 0.94f;
        }
        if (matchesSyllablePrefixes(inputKey.full(), candidateKey.syllables())) {
            return 0.9f;
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

    private static boolean containsChinese(String text) {
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
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