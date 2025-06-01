package org.es.tok.lucene;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CategoryTokenizer extends Tokenizer {
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
    private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class);

    // Character class definitions (Java regex format)
    private static final String CH_ARAB = "\\d";
    private static final String CH_ATOZ = "a-zA-Zα-ωΑ-Ω";
    private static final String CH_CJK = "\\u4E00-\\u9FFF\\u3040-\\u30FF〇";
    private static final String CH_DASH = "\\-\\+\\_\\.";
    private static final String CH_MASK = "▂";

    // Token type patterns
    private static final String RE_ARAB = "[" + CH_ARAB + "]+";
    private static final String RE_ATOZ = "[" + CH_ATOZ + "]+";
    private static final String RE_CJK = "[" + CH_CJK + "]+";
    private static final String RE_DASH = "[" + CH_DASH + "]+";
    private static final String RE_MASK = "[" + CH_MASK + "]+";
    private static final String RE_NORD = "[^" + CH_ARAB + CH_ATOZ + CH_CJK + CH_DASH + CH_MASK + "]+";

    // Combined pattern with named groups
    private static final String RE_CATEG = "(" + RE_ARAB + ")|" +
            "(" + RE_ATOZ + ")|" +
            "(" + RE_CJK + ")|" +
            "(" + RE_DASH + ")|" +
            "(" + RE_MASK + ")|" +
            "(" + RE_NORD + ")";

    private static final Pattern PT_CATEG = Pattern.compile(RE_CATEG);

    // Token types
    public enum TokenType {
        ARAB("arab"),
        ATOZ("atoz"),
        CJK("cjk"),
        DASH("dash"),
        MASK("mask"),
        NORD("nord");

        private final String name;

        TokenType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    public static class CategorizedToken {
        private final String text;
        private final int startOffset;
        private final int endOffset;
        private final TokenType type;

        public CategorizedToken(String text, int startOffset, int endOffset, TokenType type) {
            this.text = text;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.type = type;
        }

        public String getText() {
            return text;
        }

        public int getStartOffset() {
            return startOffset;
        }

        public int getEndOffset() {
            return endOffset;
        }

        public TokenType getType() {
            return type;
        }
    }

    private String inputText;
    private Iterator<CategorizedToken> tokenIterator;
    private boolean isInitialized = false;

    @Override
    public boolean incrementToken() throws IOException {
        if (!isInitialized) {
            initialize();
        }

        if (tokenIterator != null && tokenIterator.hasNext()) {
            CategorizedToken token = tokenIterator.next();
            clearAttributes();

            termAtt.copyBuffer(token.getText().toCharArray(), 0, token.getText().length());
            offsetAtt.setOffset(token.getStartOffset(), token.getEndOffset());
            posIncrAtt.setPositionIncrement(1);
            typeAtt.setType(token.getType().getName());

            return true;
        }

        return false;
    }

    private void initialize() throws IOException {
        if (isInitialized) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[8192];
        int numChars;

        while ((numChars = input.read(buffer)) != -1) {
            sb.append(buffer, 0, numChars);
        }

        inputText = sb.toString();
        List<CategorizedToken> tokens = categorizeText(inputText);
        tokenIterator = tokens.iterator();
        isInitialized = true;
    }

    private List<CategorizedToken> categorizeText(String text) {
        List<CategorizedToken> tokens = new ArrayList<>();
        Matcher matcher = PT_CATEG.matcher(text);

        while (matcher.find()) {
            String matchedText = matcher.group();
            int start = matcher.start();
            int end = matcher.end();
            TokenType type = determineTokenType(matcher);

            tokens.add(new CategorizedToken(matchedText, start, end, type));
        }

        return tokens;
    }

    private TokenType determineTokenType(Matcher matcher) {
        // Check which group matched (corresponding to pattern order)
        if (matcher.group(1) != null)
            return TokenType.ARAB;
        if (matcher.group(2) != null)
            return TokenType.ATOZ;
        if (matcher.group(3) != null)
            return TokenType.CJK;
        if (matcher.group(4) != null)
            return TokenType.DASH;
        if (matcher.group(5) != null)
            return TokenType.MASK;
        if (matcher.group(6) != null)
            return TokenType.NORD;

        return TokenType.NORD; // fallback
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        isInitialized = false;
        tokenIterator = null;
        inputText = null;
    }
}