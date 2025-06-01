package org.es.tok.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractIndexAnalyzerProvider;
import org.es.tok.lucene.VocabTokenizer;

import java.util.List;

public class VocabAnalyzerProvider extends AbstractIndexAnalyzerProvider<VocabAnalyzer> {
    private final VocabAnalyzer analyzer;

    public VocabAnalyzerProvider(IndexSettings indexSettings, Environment environment, String name,
            Settings settings) {
        super(name, settings);

        List<String> vocabulary = settings.getAsList("vocabulary");
        boolean caseSensitive = settings.getAsBoolean("case_sensitive", false);

        this.analyzer = new VocabAnalyzer(vocabulary, caseSensitive);
    }

    @Override
    public VocabAnalyzer get() {
        return this.analyzer;
    }
}

class VocabAnalyzer extends Analyzer {
    private final List<String> vocabulary;
    private final boolean caseSensitive;

    public VocabAnalyzer(List<String> vocabulary, boolean caseSensitive) {
        this.vocabulary = vocabulary;
        this.caseSensitive = caseSensitive;
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        var tokenizer = new VocabTokenizer(vocabulary, caseSensitive);

        if (!caseSensitive) {
            var lowerCaseFilter = new LowerCaseFilter(tokenizer);
            return new TokenStreamComponents(tokenizer, lowerCaseFilter);
        }

        return new TokenStreamComponents(tokenizer);
    }
}