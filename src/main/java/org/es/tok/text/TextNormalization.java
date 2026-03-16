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
        String collapsed = collapseWhitespace(value);
        if (collapsed.indexOf(' ') >= 0 && hasCjkDominance(collapsed)) {
            return collapsed.replace(" ", "").toLowerCase(Locale.ROOT);
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

    public static String collapseWhitespace(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return String.join(" ", value.trim().split("\\s+"));
    }

    public static String tightenCjkAdjacentWhitespace(String value) {
        if (value == null || value.isBlank() || value.indexOf(' ') < 0) {
            return value == null ? "" : value;
        }
        if (hasCjkDominance(value)) {
            return value.replace(" ", "");
        }
        return value;
    }

    public static String compactWhitespaceAroundCjk(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        int[] codePoints = value.codePoints().toArray();
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            if (Character.isWhitespace(codePoint)) {
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
        return collapseWhitespace(builder.toString());
    }

    private static boolean hasCjkDominance(String value) {
        int ascii = 0;
        int cjk = 0;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
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

    private static int previousNonWhitespace(int[] codePoints, int index) {
        for (int offset = index; offset >= 0; offset--) {
            if (!Character.isWhitespace(codePoints[offset])) {
                return codePoints[offset];
            }
        }
        return -1;
    }

    private static int nextNonWhitespace(int[] codePoints, int index) {
        for (int offset = index; offset < codePoints.length; offset++) {
            if (!Character.isWhitespace(codePoints[offset])) {
                return codePoints[offset];
            }
        }
        return -1;
    }

    private static boolean isHanCodePoint(int codePoint) {
        return codePoint >= 0 && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private TextNormalization() {
    }
}