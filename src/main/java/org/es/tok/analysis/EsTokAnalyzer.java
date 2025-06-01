package org.es.tok.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.es.tok.tokenize.EsTokTokenizer;

import java.util.List;

public class EsTokAnalyzer extends Analyzer {
    private final boolean enableVocab;
    private final boolean enableCateg;
    private final List<String> vocabs;
    private final boolean caseSensitive;

    public EsTokAnalyzer(boolean enableVocab, boolean enableCateg, List<String> vocabs, boolean caseSensitive) {
        this.enableVocab = enableVocab;
        this.enableCateg = enableCateg;
        this.vocabs = vocabs;
        this.caseSensitive = caseSensitive;
    }

    // Backwards compatibility constructor
    public EsTokAnalyzer(List<String> vocabs, boolean caseSensitive) {
        this(true, false, vocabs, caseSensitive);
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        EsTokTokenizer tokenizer = new EsTokTokenizer(enableVocab, enableCateg, vocabs, caseSensitive);

        if (!caseSensitive) {
            LowerCaseFilter lowerCaseFilter = new LowerCaseFilter(tokenizer);
            return new TokenStreamComponents(tokenizer, lowerCaseFilter);
        }

        return new TokenStreamComponents(tokenizer);
    }

    @Override
    public String toString() {
        return String.format("EsTokAnalyzer{enableVocab=%s, enableCateg=%s, vocabs=%d terms, caseSensitive=%s}",
                enableVocab, enableCateg, vocabs != null ? vocabs.size() : 0, caseSensitive);
    }
}