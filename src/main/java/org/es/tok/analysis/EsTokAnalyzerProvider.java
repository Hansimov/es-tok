package org.es.tok.analysis;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractIndexAnalyzerProvider;
import org.es.tok.config.EsTokConfig;
import org.es.tok.config.EsTokConfigLoader;
import org.es.tok.strategy.VocabStrategy;
import org.es.tok.strategy.CategStrategy;
import org.es.tok.strategy.NgramStrategy;
import org.es.tok.extra.HantToHansConverter;

import java.io.IOException;

public class EsTokAnalyzerProvider extends AbstractIndexAnalyzerProvider<EsTokAnalyzer> {
    private final EsTokAnalyzer analyzer;

    public EsTokAnalyzerProvider(IndexSettings indexSettings, Environment environment, String name, Settings settings) {
        super(name);

        EsTokConfig config = EsTokConfigLoader.loadConfig(settings, environment);

        // Pre-initialize strategies here with proper error handling
        VocabStrategy vocabStrategy = null;
        CategStrategy categStrategy = null;
        NgramStrategy ngramStrategy = null;
        HantToHansConverter hantToHansConverter = null;

        try {
            vocabStrategy = config.getVocabConfig().isUseVocab()
                    ? new VocabStrategy(config.getVocabConfig().getVocabs())
                    : null;
            categStrategy = config.getCategConfig().isUseCateg()
                    ? new CategStrategy(config.getCategConfig().isSplitWord())
                    : null;
            ngramStrategy = config.getNgramConfig().hasAnyNgramEnabled()
                    ? new NgramStrategy(config.getNgramConfig())
                    : null;

            // Initialize HantToHansConverter with proper error handling
            if (config.getExtraConfig().isIgnoreHant()) {
                try {
                    hantToHansConverter = HantToHansConverter.getInstance();
                } catch (IOException e) {
                    // Log error but continue without traditional Chinese conversion
                    System.err.println(
                            "Warning: Failed to initialize HantToHansConverter, traditional Chinese conversion will be disabled: "
                                    + e.getMessage());
                    // Leave hantToHansConverter as null, the tokenizer will handle this gracefully
                }
            }

            this.analyzer = new EsTokAnalyzer(config, vocabStrategy, categStrategy, ngramStrategy, hantToHansConverter);

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize EsTokAnalyzer: " + e.getMessage(), e);
        }
    }

    @Override
    public EsTokAnalyzer get() {
        return this.analyzer;
    }
}