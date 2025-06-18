package org.es.tok.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.es.tok.ngram.NgramConfig;
import org.es.tok.tokenize.EsTokTokenizer;

import java.util.List;

public class EsTokAnalyzer extends Analyzer {
    private final boolean useVocab;
    private final boolean useCateg;
    private final List<String> vocabs;
    private final boolean ignoreCase;
    private final boolean splitWord;
    private final NgramConfig ngramConfig;

    public EsTokAnalyzer(boolean useVocab, boolean useCateg, List<String> vocabs,
            boolean ignoreCase, boolean splitWord) {
        this(useVocab, useCateg, vocabs, ignoreCase, splitWord, new NgramConfig(false, false, false, false));
    }

    public EsTokAnalyzer(boolean useVocab, boolean useCateg, List<String> vocabs,
            boolean ignoreCase, boolean splitWord, NgramConfig ngramConfig) {
        this.useVocab = useVocab;
        this.useCateg = useCateg;
        this.vocabs = vocabs;
        this.ignoreCase = ignoreCase;
        this.splitWord = splitWord;
        this.ngramConfig = ngramConfig;
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        EsTokTokenizer tokenizer = new EsTokTokenizer(useVocab, useCateg, vocabs, ignoreCase, splitWord, ngramConfig);
        return new TokenStreamComponents(tokenizer);
    }

    @Override
    public String toString() {
        return String.format("EsTokAnalyzer{useVocab=%s, useCateg=%s, vocabs=%d terms, ignoreCase=%s, splitWord=%s, ngramConfig=%s}",
                useVocab, useCateg, vocabs != null ? vocabs.size() : 0, ignoreCase, splitWord, ngramConfig);
    }
}