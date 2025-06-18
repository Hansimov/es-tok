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

        this.vocabStrategy = config.getVocabConfig().isUseVocab() ? 
            new VocabStrategy(config.getVocabConfig().getVocabs(), config.isIgnoreCase()) : null;
        this.categStrategy = config.getCategConfig().isUseCateg() ? 
            new CategStrategy(config.isIgnoreCase(), config.getCategConfig().isSplitWord()) : null;
        this.ngramStrategy = config.getNgramConfig().hasAnyNgramEnabled() ? 
            new NgramStrategy(config.getNgramConfig()) : null;
    }

    // Backward compatibility constructor
    public EsTokTokenizer(org.es.tok.vocab.VocabConfig vocabConfig, 
                         org.es.tok.categ.CategConfig categConfig, 
                         org.es.tok.ngram.NgramConfig ngramConfig) {
        this(new EsTokConfig(vocabConfig, categConfig, ngramConfig, true, false));
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
        List<TokenStrategy.TokenInfo> baseTokens = new ArrayList<>();

        // Generate base tokens from categ and vocab strategies
        if (config.getCategConfig().isUseCateg() && categStrategy != null) {
            baseTokens.addAll(categStrategy.tokenize(text));
        }

        if (config.getVocabConfig().isUseVocab() && vocabStrategy != null) {
            List<TokenStrategy.TokenInfo> vocabTokens = vocabStrategy.tokenize(text);
            baseTokens.addAll(vocabTokens);
        }

        // Remove duplicates if enabled
        if (config.isDropDuplicates()) {
            baseTokens = removeDuplicateTokens(baseTokens);
        }

        // Sort base tokens by start_offset, then by type (`vocab` first)
        baseTokens.sort((a, b) -> {
            int offsetCompare = Integer.compare(a.getStartOffset(), b.getStartOffset());
            if (offsetCompare != 0)
                return offsetCompare;
            if ("vocab".equals(a.getType()) && !"vocab".equals(b.getType())) {
                return -1;
            }
            if (!"vocab".equals(a.getType()) && "vocab".equals(b.getType())) {
                return 1;
            }
            return 0;
        });

        // Generate n-grams if enabled
        List<TokenStrategy.TokenInfo> allTokens = new ArrayList<>(baseTokens);
        if (ngramStrategy != null) {
            List<TokenStrategy.TokenInfo> ngramTokens = ngramStrategy.generateNgrams(baseTokens);
            allTokens.addAll(ngramTokens);
        }

        return allTokens;
    }

    private List<TokenStrategy.TokenInfo> removeDuplicateTokens(List<TokenStrategy.TokenInfo> tokens) {
        // Use LinkedHashSet to maintain insertion order while removing duplicates
        Set<TokenKey> seenTokens = new LinkedHashSet<>();
        List<TokenStrategy.TokenInfo> uniqueTokens = new ArrayList<>();

        for (TokenStrategy.TokenInfo token : tokens) {
            TokenKey key = new TokenKey(token.getStartOffset(), token.getEndOffset());
            if (seenTokens.add(key)) {
                uniqueTokens.add(token);
            }
        }

        return uniqueTokens;
    }

    // Helper class to represent a token's position for deduplication
    private static class TokenKey {
        private final int startOffset;
        private final int endOffset;

        public TokenKey(int startOffset, int endOffset) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TokenKey tokenKey = (TokenKey) o;
            return startOffset == tokenKey.startOffset && endOffset == tokenKey.endOffset;
        }

        @Override
        public int hashCode() {
            return 31 * startOffset + endOffset;
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