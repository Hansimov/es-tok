package org.es.tok.analysis;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractIndexAnalyzerProvider;

import java.util.List;
import java.util.Arrays;

public class EsTokAnalyzerProvider extends AbstractIndexAnalyzerProvider<EsTokAnalyzer> {
    private final EsTokAnalyzer analyzer;

    public EsTokAnalyzerProvider(IndexSettings indexSettings, Environment environment, String name, Settings settings) {
        super(name, settings);

        // Default vocabulary if none provided
        List<String> vocabulary = settings.getAsList("vocabulary", Arrays.asList());
        List<String> vocabs = settings.getAsList("vocabs", vocabulary); // Support both 'vocabs' and 'vocabulary'
        boolean caseSensitive = settings.getAsBoolean("case_sensitive", false);

        this.analyzer = new EsTokAnalyzer(vocabs, caseSensitive);
    }

    @Override
    public EsTokAnalyzer get() {
        return this.analyzer;
    }
}