package org.es.tok.lucene;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.es.tok.lucene.strategy.CategStrategy;
import org.es.tok.lucene.strategy.TokenStrategy;
import org.es.tok.lucene.strategy.VocabStrategy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class EsTokTokenizer extends Tokenizer {
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
    private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class);

    private final boolean enableVocab;
    private final boolean enableCateg;
    private final VocabStrategy VocabStrategy;
    private final CategStrategy CategStrategy;

    private String inputText;
    private Iterator<TokenStrategy.TokenInfo> tokenIterator;
    private boolean isInitialized = false;

    public EsTokTokenizer(boolean enableVocab, boolean enableCateg, List<String> vocabs, boolean caseSensitive) {
        this.enableVocab = enableVocab;
        this.enableCateg = enableCateg;

        // Initialize strategies based on configuration
        this.VocabStrategy = enableVocab ? new VocabStrategy(vocabs, caseSensitive) : null;
        this.CategStrategy = enableCateg ? new CategStrategy() : null;

        // Validation: at least one strategy must be enabled
        if (!enableVocab && !enableCateg) {
            throw new IllegalArgumentException("At least one of enable_vocab or enable_categ must be true");
        }
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
        List<TokenStrategy.TokenInfo> allTokens = new ArrayList<>();

        // Apply categorization strategy if enabled
        if (enableCateg && CategStrategy != null) {
            allTokens.addAll(CategStrategy.tokenize(text));
        }

        // Apply vocabulary strategy if enabled
        if (enableVocab && VocabStrategy != null) {
            List<TokenStrategy.TokenInfo> vocabTokens = VocabStrategy.tokenize(text);
            allTokens.addAll(vocabTokens);
        }

        // Sort tokens by start offset, then by type priority (vocab tokens first)
        allTokens.sort((a, b) -> {
            int offsetCompare = Integer.compare(a.getStartOffset(), b.getStartOffset());
            if (offsetCompare != 0)
                return offsetCompare;

            // Prioritize vocab tokens over categorization tokens at same position
            if ("vocab".equals(a.getType()) && !"vocab".equals(b.getType()))
                return -1;
            if (!"vocab".equals(a.getType()) && "vocab".equals(b.getType()))
                return 1;
            return 0;
        });

        return allTokens;
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        isInitialized = false;
        tokenIterator = null;
        inputText = null;
    }
}