package org.es.tok.tokenize;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.es.tok.config.EsTokConfig;
import org.es.tok.core.facade.EsTokEngine;
import org.es.tok.extra.HantToHansConverter;
import org.es.tok.suggest.PinyinSupport;
import org.es.tok.strategy.CategStrategy;
import org.es.tok.strategy.NgramStrategy;
import org.es.tok.strategy.TokenStrategy;
import org.es.tok.strategy.VocabStrategy;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;

public final class EsTokTokenizer extends Tokenizer {
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
    private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class);
    private final GroupAttribute groupAtt = addAttribute(GroupAttribute.class);

    private final EsTokConfig config;
    private final EsTokEngine engine;

    private String inputText;
    private Iterator<TokenStrategy.TokenInfo> tokenIterator;
    private final Queue<TokenStrategy.TokenInfo> pendingTokens = new ArrayDeque<>();
    private boolean isInitialized = false;

    // New constructor using EsTokConfig
    public EsTokTokenizer(EsTokConfig config) {
        this.config = config;
        if (!config.getVocabConfig().isUseVocab() && !config.getCategConfig().isUseCateg()) {
            throw new IllegalArgumentException("Must use at least one strategy: use_vocab, use_categ");
        }

        VocabStrategy vocabStrategy = config.getVocabConfig().isUseVocab()
                ? new VocabStrategy(config.getVocabConfig().getVocabs())
                : null;
        CategStrategy categStrategy = config.getCategConfig().isUseCateg()
                ? new CategStrategy(config.getCategConfig().isSplitWord())
                : null;
        NgramStrategy ngramStrategy = config.getNgramConfig().hasAnyNgramEnabled()
                ? new NgramStrategy(config.getNgramConfig())
                : null;

        HantToHansConverter hantToHansConverter;
        if (config.getExtraConfig().isIgnoreHant()) {
            try {
                hantToHansConverter = HantToHansConverter.getInstance();
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize HantToHansConverter", e);
            }
        } else {
            hantToHansConverter = null;
        }

        this.engine = new EsTokEngine(config, vocabStrategy, categStrategy, ngramStrategy, hantToHansConverter);
    }

    // Performance-optimized constructor using pre-built strategies
    public EsTokTokenizer(EsTokConfig config, VocabStrategy vocabStrategy, CategStrategy categStrategy,
            NgramStrategy ngramStrategy, HantToHansConverter hantToHansConverter) {
        this.config = config;
        if (vocabStrategy == null && categStrategy == null) {
            throw new IllegalArgumentException("Must use at least one strategy: use_vocab, use_categ");
        }

        this.engine = new EsTokEngine(config, vocabStrategy, categStrategy, ngramStrategy, hantToHansConverter);
    }

    @Override
    public final boolean incrementToken() throws IOException {
        if (!isInitialized) {
            initialize();
        }

        while (pendingTokens.isEmpty() && tokenIterator != null && tokenIterator.hasNext()) {
            enqueueToken(tokenIterator.next());
        }

        if (!pendingTokens.isEmpty()) {
            TokenStrategy.TokenInfo token = pendingTokens.poll();
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
        tokenIterator = engine.analyzeToTokenInfos(inputText).iterator();
        isInitialized = true;
    }

    private void enqueueToken(TokenStrategy.TokenInfo token) {
        pendingTokens.add(token);
        if (!config.getExtraConfig().isEmitPinyinTerms()) {
            return;
        }

        List<String> pinyinTerms = PinyinSupport.precomputedSuggestionTerms(token.getText());
        for (String pinyinTerm : pinyinTerms) {
            pendingTokens.add(new TokenStrategy.TokenInfo(
                    pinyinTerm,
                    token.getStartOffset(),
                    token.getEndOffset(),
                    "pinyin",
                    token.getPosition(),
                    "pinyin"));
        }
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        isInitialized = false;
        tokenIterator = null;
        inputText = null;
        pendingTokens.clear();
    }
}