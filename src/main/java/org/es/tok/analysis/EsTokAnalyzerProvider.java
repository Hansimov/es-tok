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

        // Parse configuration
        boolean enableVocab = settings.getAsBoolean("enable_vocab", true);
        boolean enableCateg = settings.getAsBoolean("enable_categ", false);
        List<String> vocabs = settings.getAsList("vocabs", Arrays.asList());
        boolean caseSensitive = settings.getAsBoolean("case_sensitive", false);

        // Support legacy 'vocabulary' parameter
        if (vocabs.isEmpty()) {
            vocabs = settings.getAsList("vocabulary", Arrays.asList());
        }

        this.analyzer = new EsTokAnalyzer(enableVocab, enableCateg, vocabs, caseSensitive);
    }

    @Override
    public EsTokAnalyzer get() {
        return this.analyzer;
    }
}