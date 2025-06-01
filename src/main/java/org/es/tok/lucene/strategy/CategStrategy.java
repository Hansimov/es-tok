package org.es.tok.lucene.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CategStrategy implements TokenAction {
    // Character class definitions (from existing CategTokenizer)
    private static final String CH_ARAB = "\\d";
    private static final String CH_ATOZ = "a-zA-Zα-ωΑ-Ω";
    private static final String CH_CJK = "\\u4E00-\\u9FFF\\u3040-\\u30FF〇";
    private static final String CH_DASH = "\\-\\+\\_\\.";
    private static final String CH_MASK = "▂";

    private static final String RE_ARAB = "[" + CH_ARAB + "]+";
    private static final String RE_ATOZ = "[" + CH_ATOZ + "]+";
    private static final String RE_CJK = "[" + CH_CJK + "]+";
    private static final String RE_DASH = "[" + CH_DASH + "]+";
    private static final String RE_MASK = "[" + CH_MASK + "]+";
    private static final String RE_NORD = "[^" + CH_ARAB + CH_ATOZ + CH_CJK + CH_DASH + CH_MASK + "]+";

    private static final String RE_CATEG = "(" + RE_ARAB + ")|(" + RE_ATOZ + ")|(" + RE_CJK + ")|(" + RE_DASH + ")|("
            + RE_MASK + ")|(" + RE_NORD + ")";
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
        if (matcher.group(1) != null)
            return "arab";
        if (matcher.group(2) != null)
            return "atoz";
        if (matcher.group(3) != null)
            return "cjk";
        if (matcher.group(4) != null)
            return "dash";
        if (matcher.group(5) != null)
            return "mask";
        if (matcher.group(6) != null)
            return "nord";
        return "nord";
    }
}