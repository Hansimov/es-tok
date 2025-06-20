package org.es.tok.tokenize;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.es.tok.config.EsTokConfig;
import org.es.tok.strategy.CategStrategy;
import org.es.tok.strategy.NgramStrategy;
import org.es.tok.strategy.TokenStrategy;
import org.es.tok.strategy.VocabStrategy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class EsTokTokenizer extends Tokenizer {
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
    private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class);
    private final GroupAttribute groupAtt = addAttribute(GroupAttribute.class);

    private final EsTokConfig config;
    private final VocabStrategy vocabStrategy;
    private final CategStrategy categStrategy;
    private final NgramStrategy ngramStrategy;

    private String inputText;
    private Iterator<TokenStrategy.TokenInfo> tokenIterator;
    private boolean isInitialized = false;

    // New constructor using EsTokConfig
    public EsTokTokenizer(EsTokConfig config) {
        this.config = config;

        if (!config.getVocabConfig().isUseVocab() && !config.getCategConfig().isUseCateg()) {
            throw new IllegalArgumentException("Must use at least one strategy: use_vocab, use_categ");
        }

        this.vocabStrategy = config.getVocabConfig().isUseVocab()
                ? new VocabStrategy(config.getVocabConfig().getVocabs(), config.isIgnoreCase())
                : null;
        this.categStrategy = config.getCategConfig().isUseCateg()
                ? new CategStrategy(config.isIgnoreCase(), config.getCategConfig().isSplitWord())
                : null;
        this.ngramStrategy = config.getNgramConfig().hasAnyNgramEnabled() ? new NgramStrategy(config.getNgramConfig())
                : null;
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (!isInitialized) {
            initialize();
        }

        if (tokenIterator != null && tokenIterator.hasNext()) {
            TokenStrategy.TokenInfo token = tokenIterator.next();
            clearAttributes();

            termAtt.copyBuffer(token.getText().toCharArray(), 0, token.getText().length());
            offsetAtt.setOffset(token.getStartOffset(), token.getEndOffset());
            posIncrAtt.setPositionIncrement(1);
            typeAtt.setType(token.getType());
            groupAtt.setGroup(token.getGroup());

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
        List<TokenStrategy.TokenInfo> allTokens = processText(inputText);
        tokenIterator = allTokens.iterator();
        isInitialized = true;
    }

    private List<TokenStrategy.TokenInfo> processText(String text) {
        // Step 1: Generate base tokens from categ and vocab strategies
        List<TokenStrategy.TokenInfo> baseTokens = generateBaseTokens(text);

        // Step 2: Apply first deduplication on base tokens if enabled
        if (config.isDropDuplicates()) {
            baseTokens = dropDuplicatedTokens(baseTokens);
        }

        // Step 3: Sort base tokens
        baseTokens = sortTokens(baseTokens);

        // Step 4: Generate n-grams if enabled
        List<TokenStrategy.TokenInfo> allTokens = new ArrayList<>(baseTokens);
        if (ngramStrategy != null) {
            List<TokenStrategy.TokenInfo> ngramTokens = ngramStrategy.generateNgrams(baseTokens);
            allTokens.addAll(ngramTokens);
        }

        // Step 5: Apply final deduplication after n-gram generation if enabled
        if (config.isDropDuplicates()) {
            allTokens = dropDuplicatedTokens(allTokens);
        }

        return allTokens;
    }

    // Generate base tokens from categ and vocab strategies
    private List<TokenStrategy.TokenInfo> generateBaseTokens(String text) {
        List<TokenStrategy.TokenInfo> baseTokens = new ArrayList<>();

        // Generate tokens from categ strategy
        if (config.getCategConfig().isUseCateg() && categStrategy != null) {
            baseTokens.addAll(categStrategy.tokenize(text));
        }

        // Generate tokens from vocab strategy
        if (config.getVocabConfig().isUseVocab() && vocabStrategy != null) {
            List<TokenStrategy.TokenInfo> vocabTokens = vocabStrategy.tokenize(text);
            baseTokens.addAll(vocabTokens);
        }

        return baseTokens;
    }

    // Sort tokens by start_offset, then by type ('vocab' first)
    private List<TokenStrategy.TokenInfo> sortTokens(List<TokenStrategy.TokenInfo> tokens) {
        List<TokenStrategy.TokenInfo> sortedTokens = new ArrayList<>(tokens);
        sortedTokens.sort((a, b) -> {
            // First, compare by start offset
            int offsetCompare = Integer.compare(a.getStartOffset(), b.getStartOffset());
            if (offsetCompare != 0) {
                return offsetCompare;
            }

            // If same start offset and same type priority, compare by end offset
            int endOffsetCompare = Integer.compare(a.getEndOffset(), b.getEndOffset());
            if (endOffsetCompare != 0) {
                return endOffsetCompare;
            }

            // Finally, compare by token text for consistent ordering
            return a.getText().compareTo(b.getText());
        });

        return sortedTokens;
    }

    // Remove duplicated tokens based on: token text, start_offset, and end_offset
    private List<TokenStrategy.TokenInfo> dropDuplicatedTokens(List<TokenStrategy.TokenInfo> tokens) {
        Set<TokenKey> seenTokens = new LinkedHashSet<>();
        List<TokenStrategy.TokenInfo> uniqueTokens = new ArrayList<>();

        for (TokenStrategy.TokenInfo token : tokens) {
            TokenKey key = new TokenKey(token.getText(), token.getStartOffset(), token.getEndOffset());
            if (seenTokens.add(key)) {
                uniqueTokens.add(token);
            }
        }

        return uniqueTokens;
    }

    // unique token by: (text, start_offset, end_offset)
    private static class TokenKey {
        private final String text;
        private final int startOffset;
        private final int endOffset;

        public TokenKey(String text, int startOffset, int endOffset) {
            this.text = text;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            TokenKey tokenKey = (TokenKey) o;
            return startOffset == tokenKey.startOffset &&
                    endOffset == tokenKey.endOffset &&
                    text.equals(tokenKey.text);
        }

        @Override
        public int hashCode() {
            int result = text.hashCode();
            result = 31 * result + startOffset;
            result = 31 * result + endOffset;
            return result;
        }

        @Override
        public String toString() {
            return String.format("TokenKey{text='%s', startOffset=%d, endOffset=%d}",
                    text, startOffset, endOffset);
        }
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        isInitialized = false;
        tokenIterator = null;
        inputText = null;
    }
}