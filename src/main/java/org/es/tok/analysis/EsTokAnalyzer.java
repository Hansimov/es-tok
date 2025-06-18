package org.es.tok.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.es.tok.categ.CategConfig;
import org.es.tok.config.EsTokConfig;
import org.es.tok.ngram.NgramConfig;
import org.es.tok.tokenize.EsTokTokenizer;
import org.es.tok.vocab.VocabConfig;

public class EsTokAnalyzer extends Analyzer {
    private final EsTokConfig config;

    // New constructor using EsTokConfig
    public EsTokAnalyzer(EsTokConfig config) {
        this.config = config;
    }

    // Backward compatibility constructors
    public EsTokAnalyzer(VocabConfig vocabConfig, CategConfig categConfig) {
        this(vocabConfig, categConfig, new NgramConfig(false, false, false, false), true, false);
    }

    public EsTokAnalyzer(VocabConfig vocabConfig, CategConfig categConfig, NgramConfig ngramConfig) {
        this(vocabConfig, categConfig, ngramConfig, true, false);
    }

    public EsTokAnalyzer(VocabConfig vocabConfig, CategConfig categConfig, NgramConfig ngramConfig, boolean ignoreCase) {
        this(vocabConfig, categConfig, ngramConfig, ignoreCase, false);
    }

    public EsTokAnalyzer(VocabConfig vocabConfig, CategConfig categConfig, NgramConfig ngramConfig, boolean ignoreCase, boolean dropDuplicates) {
        this.config = new EsTokConfig(vocabConfig, categConfig, ngramConfig, ignoreCase, dropDuplicates);
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        EsTokTokenizer tokenizer = new EsTokTokenizer(config);
        return new TokenStreamComponents(tokenizer);
    }

    @Override
    public String toString() {
        return String.format("EsTokAnalyzer{config=%s}", config);
    }
}