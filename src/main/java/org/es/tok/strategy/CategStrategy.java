package org.es.tok.strategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CategStrategy implements TokenStrategy {
    // Unicode Character Ranges
    // * https://jrgraphix.net/research/unicode_blocks.php
    private static final String CH_ARAB = "\\d";
    private static final String CH_ENG = "a-zA-Z";
    private static final String CH_CJK = "\\u2e80-\\u2fdf\\u3040-\\u30FF\\u4E00-\\u9FFF\\uf900-\\ufaff〇";
    private static final String CH_LANG = "\\u0391-\\u03c9\\u0410-\\u044f\\u0e01-\\u0e5b";
    private static final String CH_DASH = "\\-\\+\\_\\.";
    private static final String CH_WS = "\\s";
    private static final String CH_MASK = "▂";

    private static final String RE_ARAB = "[%s]+".formatted(CH_ARAB);
    private static final String RE_ENG = "[%s]+".formatted(CH_ENG);
    private static final String RE_CJK = "[%s]+".formatted(CH_CJK);
    private static final String RE_LANG = "[%s]+".formatted(CH_LANG);
    private static final String RE_DASH = "[%s]+".formatted(CH_DASH);
    private static final String RE_WS = "[%s]+".formatted(CH_WS);
    private static final String RE_MASK = "[%s]+".formatted(CH_MASK);
    private static final String RE_NORD = "[^%s]+".formatted(
            RE_ARAB + RE_ENG + RE_CJK + RE_LANG + RE_DASH + RE_WS + RE_MASK);

    private static final String RE_CATEG = "(?<arab>%s)|(?<eng>%s)|(?<cjk>%s)|(?<lang>%s)|(?<dash>%s)|(?<ws>%s)|(?<mask>%s)|(?<nord>%s)"
            .formatted(RE_ARAB, RE_ENG, RE_CJK, RE_LANG, RE_DASH, RE_WS, RE_MASK, RE_NORD);
    private static final Pattern PT_CATEG = Pattern.compile(RE_CATEG);

    private static final List<String> GROUP_NAMES = Arrays.asList(
            "arab", "eng", "cjk", "lang", "dash", "ws", "mask", "nord");

    private final boolean ignoreCase;
    private final boolean splitWord;

    public CategStrategy(boolean ignoreCase, boolean splitWord) {
        this.ignoreCase = ignoreCase;
        this.splitWord = splitWord;
    }

    @Override
    public List<TokenInfo> tokenize(String text) {
        List<TokenInfo> tokens = new ArrayList<>();
        Matcher matcher = PT_CATEG.matcher(text);
        int position = 0;

        while (matcher.find()) {
            String matchText = matcher.group();
            String tokenText = ignoreCase ? matchText.toLowerCase() : matchText;
            int start = matcher.start();
            int end = matcher.end();
            String type = determineTokenType(matcher);
            if (splitWord && ("cjk".equals(type) || "lang".equals(type))) {
                for (int i = start; i < end; i++) {
                    tokens.add(new TokenInfo(
                            String.valueOf(tokenText.charAt(i - start)),
                            i, i + 1, type, position++));
                }
            } else {
                tokens.add(new TokenInfo(tokenText, start, end, type, position++));
            }
        }

        return tokens;
    }

    private String determineTokenType(Matcher matcher) {
        for (String groupName : GROUP_NAMES) {
            if (matcher.group(groupName) != null) {
                return groupName;
            }
        }
        return "nord";
    }
}