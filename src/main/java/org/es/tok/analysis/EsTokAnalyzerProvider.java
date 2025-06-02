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
        boolean useVocab = settings.getAsBoolean("use_vocab", true);
        boolean useCateg = settings.getAsBoolean("use_categ", false);
        List<String> vocabs = settings.getAsList("vocabs", Arrays.asList());
        boolean ignoreCase = settings.getAsBoolean("ignore_case", true);
        boolean splitWord = settings.getAsBoolean("split_word", true);

        this.analyzer = new EsTokAnalyzer(useVocab, useCateg, vocabs, ignoreCase, splitWord);
    }

    @Override
    public EsTokAnalyzer get() {
        return this.analyzer;
    }
}