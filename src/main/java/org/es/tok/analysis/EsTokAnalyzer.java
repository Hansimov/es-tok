package org.es.tok.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.es.tok.lucene.AhoCorasickTokenizer;

import java.util.List;

public class EsTokAnalyzer extends Analyzer {
    private final List<String> vocabulary;
    private final boolean caseSensitive;

    public EsTokAnalyzer(List<String> vocabulary, boolean caseSensitive) {
        this.vocabulary = vocabulary;
        this.caseSensitive = caseSensitive;
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        var tokenizer = new AhoCorasickTokenizer(vocabulary, caseSensitive);

        if (!caseSensitive) {
            var lowerCaseFilter = new LowerCaseFilter(tokenizer);
            return new TokenStreamComponents(tokenizer, lowerCaseFilter);
        }

        return new TokenStreamComponents(tokenizer);
    }

    @Override
    public String toString() {
        return "EsTokAnalyzer{vocabulary=" + vocabulary.size() + " terms, caseSensitive=" + caseSensitive + "}";
    }
}