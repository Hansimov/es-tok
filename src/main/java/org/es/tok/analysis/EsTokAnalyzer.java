package org.es.tok.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.es.tok.config.EsTokConfig;
import org.es.tok.tokenize.EsTokTokenizer;

public class EsTokAnalyzer extends Analyzer {
    private final EsTokConfig config;

    public EsTokAnalyzer(EsTokConfig config) {
        this.config = config;
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