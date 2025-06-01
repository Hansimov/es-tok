package org.es.tok.strategy;

import java.util.ArrayList;
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

    private static final String RE_ARAB = "[" + CH_ARAB + "]+";
    private static final String RE_ENG = "[" + CH_ENG + "]+";
    private static final String RE_CJK = "[" + CH_CJK + "]+";
    private static final String RE_LANG = "[" + CH_LANG + "]+";
    private static final String RE_DASH = "[" + CH_DASH + "]+";
    private static final String RE_WS = "[" + CH_WS + "]+";
    private static final String RE_MASK = "[" + CH_MASK + "]+";
    private static final String RE_NORD = "[^" + CH_ARAB + CH_ENG + CH_CJK + CH_LANG + CH_DASH + CH_WS + CH_MASK + "]+";

    private static final String RE_CATEG = "(?<arab>" + RE_ARAB + ")|(?<eng>" + RE_ENG +
            ")|(?<cjk>" + RE_CJK + ")|(?<lang>" + RE_LANG +
            ")|(?<dash>" + RE_DASH + ")|(?<ws>" + RE_WS + ")|(?<mask>" + RE_MASK + ")|(?<nord>" + RE_NORD + ")";
    private static final Pattern PT_CATEG = Pattern.compile(RE_CATEG);

    @Override
    public List<TokenInfo> tokenize(String text) {
        List<TokenInfo> tokens = new ArrayList<>();
        Matcher matcher = PT_CATEG.matcher(text);
        int position = 0;

        while (matcher.find()) {
            String matchedText = matcher.group();
            int start = matcher.start();
            int end = matcher.end();
            String type = determineTokenType(matcher);

            tokens.add(new TokenInfo(matchedText, start, end, type, position++));
        }

        return tokens;
    }

    private String determineTokenType(Matcher matcher) {
        if (matcher.group("arab") != null) {
            return "arab";
        }
        if (matcher.group("eng") != null) {
            return "eng";
        }
        if (matcher.group("cjk") != null) {
            return "cjk";
        }
        if (matcher.group("lang") != null) {
            return "lang";
        }
        if (matcher.group("dash") != null) {
            return "dash";
        }
        if (matcher.group("ws") != null) {
            return "ws";
        }
        if (matcher.group("mask") != null) {
            return "mask";
        }
        if (matcher.group("nord") != null) {
            return "nord";
        }
        return "nord";
    }
}