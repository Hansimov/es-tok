package org.es.tok.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractIndexAnalyzerProvider;
import org.es.tok.lucene.AhoCorasickTokenizer;

import java.util.List;

public class AhoCorasickAnalyzerProvider extends AbstractIndexAnalyzerProvider<AhoCorasickAnalyzer> {
    private final AhoCorasickAnalyzer analyzer;

    public AhoCorasickAnalyzerProvider(IndexSettings indexSettings, Environment environment, String name,
            Settings settings) {
        super(name, settings);

        List<String> vocabulary = settings.getAsList("vocabulary");
        boolean caseSensitive = settings.getAsBoolean("case_sensitive", false);

        this.analyzer = new AhoCorasickAnalyzer(vocabulary, caseSensitive);
    }

    @Override
    public AhoCorasickAnalyzer get() {
        return this.analyzer;
    }
}

class AhoCorasickAnalyzer extends Analyzer {
    private final List<String> vocabulary;
    private final boolean caseSensitive;

    public AhoCorasickAnalyzer(List<String> vocabulary, boolean caseSensitive) {
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
}