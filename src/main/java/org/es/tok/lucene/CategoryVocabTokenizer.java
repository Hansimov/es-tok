package org.es.tok.lucene;

import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CategoryVocabTokenizer extends Tokenizer {
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
    private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class);

    // Character class definitions
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

    public enum TokenType {
        ARAB("arab"),
        ATOZ("atoz"),
        CJK("cjk"),
        DASH("dash"),
        MASK("mask"),
        NORD("nord"),
        VOCAB("vocab"); // For Aho-Corasick matches

        private final String name;

        TokenType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    public static class CombinedToken {
        private final String text;
        private final int startOffset;
        private final int endOffset;
        private final TokenType type;
        private final int position;

        public CombinedToken(String text, int startOffset, int endOffset, TokenType type, int position) {
            this.text = text;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.type = type;
            this.position = position;
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

        public int getPosition() {
            return position;
        }
    }

    private final Trie trie;
    private final boolean enableVocabularyMatching;
    private String inputText;
    private Iterator<CombinedToken> tokenIterator;
    private boolean isInitialized = false;

    public CategoryVocabTokenizer(List<String> vocabulary, boolean caseSensitive) {
        this(vocabulary, caseSensitive, true);
    }

    public CategoryVocabTokenizer(List<String> vocabulary, boolean caseSensitive,
            boolean enableVocabularyMatching) {
        this.enableVocabularyMatching = enableVocabularyMatching;

        if (enableVocabularyMatching && vocabulary != null && !vocabulary.isEmpty()) {
            Trie.TrieBuilder builder = Trie.builder();

            if (!caseSensitive) {
                builder.ignoreCase();
            }

            for (String word : vocabulary) {
                if (word != null && !word.trim().isEmpty()) {
                    builder.addKeyword(word.trim());
                }
            }

            this.trie = builder.build();
        } else {
            this.trie = null;
        }
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (!isInitialized) {
            initialize();
        }

        if (tokenIterator != null && tokenIterator.hasNext()) {
            CombinedToken token = tokenIterator.next();
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
        List<CombinedToken> tokens = processText(inputText);
        tokenIterator = tokens.iterator();
        isInitialized = true;
    }

    private List<CombinedToken> processText(String text) {
        // First, get categorized tokens
        List<CombinedToken> categorizedTokens = categorizeText(text);

        // If vocabulary matching is disabled, return categorized tokens only
        if (!enableVocabularyMatching || trie == null) {
            return categorizedTokens;
        }

        // Then, find vocabulary matches
        Collection<Emit> vocabMatches = trie.parseText(text);

        // Combine both types of tokens and sort by position
        List<CombinedToken> allTokens = new ArrayList<>(categorizedTokens);

        int position = categorizedTokens.size();
        for (Emit emit : vocabMatches) {
            allTokens.add(new CombinedToken(
                    emit.getKeyword(),
                    emit.getStart(),
                    emit.getEnd() + 1,
                    TokenType.VOCAB,
                    position++));
        }

        // Sort by start offset, then by type (vocab tokens first for same position)
        allTokens.sort((a, b) -> {
            int offsetCompare = Integer.compare(a.getStartOffset(), b.getStartOffset());
            if (offsetCompare != 0)
                return offsetCompare;

            // If same start offset, prioritize vocab tokens
            if (a.getType() == TokenType.VOCAB && b.getType() != TokenType.VOCAB)
                return -1;
            if (a.getType() != TokenType.VOCAB && b.getType() == TokenType.VOCAB)
                return 1;
            return 0;
        });

        return allTokens;
    }

    private List<CombinedToken> categorizeText(String text) {
        List<CombinedToken> tokens = new ArrayList<>();
        Matcher matcher = PT_CATEG.matcher(text);
        int position = 0;

        while (matcher.find()) {
            String matchedText = matcher.group();
            int start = matcher.start();
            int end = matcher.end();
            TokenType type = determineTokenType(matcher);

            tokens.add(new CombinedToken(matchedText, start, end, type, position++));
        }

        return tokens;
    }

    private TokenType determineTokenType(Matcher matcher) {
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
        return TokenType.NORD;
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        isInitialized = false;
        tokenIterator = null;
        inputText = null;
    }
}