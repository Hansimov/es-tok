package org.es.tok.tokenize;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.es.tok.ngram.NgramConfig;
import org.es.tok.strategy.CategStrategy;
import org.es.tok.strategy.NgramStrategy;
import org.es.tok.strategy.TokenStrategy;
import org.es.tok.strategy.VocabStrategy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class EsTokTokenizer extends Tokenizer {
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
    private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class);

    private final boolean useVocab;
    private final boolean useCateg;
    private final VocabStrategy vocabStrategy;
    private final CategStrategy categStrategy;
    private final NgramStrategy ngramStrategy;

    private String inputText;
    private Iterator<TokenStrategy.TokenInfo> tokenIterator;
    private boolean isInitialized = false;

    public EsTokTokenizer(boolean useVocab, boolean useCateg, List<String> vocabs,
            boolean ignoreCase, boolean splitWord, NgramConfig ngramConfig) {
        this.useVocab = useVocab;
        this.useCateg = useCateg;

        if (!useVocab && !useCateg) {
            throw new IllegalArgumentException("Must use at least one strategy: use_vocab, use_categ");
        }

        this.vocabStrategy = useVocab ? new VocabStrategy(vocabs, ignoreCase) : null;
        this.categStrategy = useCateg ? new CategStrategy(ignoreCase, splitWord) : null;
        this.ngramStrategy = ngramConfig.hasAnyNgramEnabled() ? new NgramStrategy(ngramConfig) : null;
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
        if (useCateg && categStrategy != null) {
            baseTokens.addAll(categStrategy.tokenize(text));
        }

        if (useVocab && vocabStrategy != null) {
            List<TokenStrategy.TokenInfo> vocabTokens = vocabStrategy.tokenize(text);
            baseTokens.addAll(vocabTokens);
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

    @Override
    public void reset() throws IOException {
        super.reset();
        isInitialized = false;
        tokenIterator = null;
        inputText = null;
    }
}