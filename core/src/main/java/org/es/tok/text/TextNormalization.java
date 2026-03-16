package org.es.tok.text;

import java.util.Locale;

public final class TextNormalization {
    public static String trimToEmpty(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim();
    }

    public static String normalizeLower(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeLower(Object value) {
        return normalizeLower(value == null ? null : value.toString());
    }

    public static String normalizeAnalyzedToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String collapsed = collapseWhitespace(value).strip();
        if (collapsed.indexOf(' ') >= 0 && hasCjkDominance(collapsed)) {
            return removeWhitespace(collapsed).toLowerCase(Locale.ROOT);
        }
        return collapsed.toLowerCase(Locale.ROOT);
    }

    public static String normalizeOwnerDisplayName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return tightenCjkAdjacentWhitespace(collapseWhitespace(value));
    }

    public static String normalizeOwnerLookupName(String value) {
        return tightenCjkAdjacentWhitespace(collapseWhitespace(normalizeLower(value)));
    }

    public static String normalizeSuggestionSurface(String value) {
        if (value == null) {
            return "";
        }
        String collapsed = collapseWhitespace(value).strip();
        if (collapsed.isBlank() || !containsWhitespace(collapsed)) {
            return collapsed;
        }
        String tightened = tightenCjkAdjacentWhitespace(collapsed);
        if (!containsWhitespace(tightened)) {
            return tightened;
        }
        return shouldCompactWhitespace(tightened) ? removeWhitespace(tightened) : tightened;
    }

    public static String collapseWhitespace(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        boolean previousWhitespace = false;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isGapCodePoint(codePoint)) {
                if (!previousWhitespace) {
                    builder.append(' ');
                    previousWhitespace = true;
                }
                continue;
            }
            builder.appendCodePoint(codePoint);
            previousWhitespace = false;
        }
        return builder.toString();
    }

    public static String tightenCjkAdjacentWhitespace(String value) {
        if (value == null || value.isBlank() || value.indexOf(' ') < 0) {
            return value == null ? "" : value;
        }
        StringBuilder builder = new StringBuilder(value.length());
        int[] codePoints = value.codePoints().toArray();
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            if (isGapCodePoint(codePoint)) {
                int previous = previousNonWhitespace(codePoints, index - 1);
                int next = nextNonWhitespace(codePoints, index + 1);
                if (previous >= 0 && next >= 0 && (isHanCodePoint(previous) || isHanCodePoint(next))) {
                    continue;
                }
                builder.append(' ');
                continue;
            }
            builder.appendCodePoint(codePoint);
        }
        return builder.toString().strip();
    }

    public static String compactWhitespaceAroundCjk(String value) {
        return tightenCjkAdjacentWhitespace(collapseWhitespace(value));
    }

    public static boolean containsWhitespace(String value) {
        return value != null && value.chars().anyMatch(Character::isWhitespace);
    }

    private static boolean hasCjkDominance(String value) {
        int ascii = 0;
        int cjk = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isGapCodePoint(codePoint)) {
                continue;
            }
            if (codePoint < 128 && Character.isLetterOrDigit(codePoint)) {
                ascii++;
            } else if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                cjk++;
            }
        }
        return cjk > 0 && cjk >= ascii;
    }

    private static boolean shouldCompactWhitespace(String value) {
        int asciiLettersOrDigits = 0;
        int nonAsciiNonWhitespace = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isGapCodePoint(codePoint)) {
                continue;
            }
            if (codePoint < 128 && Character.isLetterOrDigit(codePoint)) {
                asciiLettersOrDigits++;
            } else if (codePoint >= 128) {
                nonAsciiNonWhitespace++;
            }
        }
        return nonAsciiNonWhitespace > 0 && nonAsciiNonWhitespace >= asciiLettersOrDigits;
    }

    private static String removeWhitespace(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (!isGapCodePoint(codePoint)) {
                builder.appendCodePoint(codePoint);
            }
        }
        return builder.toString();
    }

    private static int previousNonWhitespace(int[] codePoints, int index) {
        while (index >= 0) {
            if (!isGapCodePoint(codePoints[index])) {
                return codePoints[index];
            }
            index--;
        }
        return -1;
    }

    private static int nextNonWhitespace(int[] codePoints, int index) {
        while (index < codePoints.length) {
            if (!isGapCodePoint(codePoints[index])) {
                return codePoints[index];
            }
            index++;
        }
        return -1;
    }

    private static boolean isGapCodePoint(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
    }

    private static boolean isHanCodePoint(int codePoint) {
        return codePoint >= 0 && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private TextNormalization() {
    }
}