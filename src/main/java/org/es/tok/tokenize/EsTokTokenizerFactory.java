package org.es.tok.tokenize;

import org.apache.lucene.analysis.Tokenizer;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenizerFactory;
import org.es.tok.config.EsTokConfig;
import org.es.tok.config.EsTokConfigLoader;
import org.es.tok.strategy.VocabStrategy;
import org.es.tok.strategy.CategStrategy;
import org.es.tok.strategy.NgramStrategy;
import org.es.tok.extra.HantToHansConverter;

import java.io.IOException;

public class EsTokTokenizerFactory extends AbstractTokenizerFactory {
    private final EsTokConfig config;
    // Cache the expensive strategy instances
    private final VocabStrategy vocabStrategy;
    private final CategStrategy categStrategy;
    private final NgramStrategy ngramStrategy;
    private final HantToHansConverter hantToHansConverter;

    public EsTokTokenizerFactory(IndexSettings indexSettings, Environment environment, String name, Settings settings) {
        super(name);

        // Load config with cache enabled for better performance
        this.config = EsTokConfigLoader.loadConfig(settings, environment, true);

        // Pre-initialize expensive strategies once during factory creation
        this.vocabStrategy = config.getVocabConfig().isUseVocab()
                ? new VocabStrategy(config.getVocabConfig().getVocabs())
                : null;
        this.categStrategy = config.getCategConfig().isUseCateg()
                ? new CategStrategy(config.getCategConfig().isSplitWord())
                : null;
        this.ngramStrategy = config.getNgramConfig().hasAnyNgramEnabled()
                ? new NgramStrategy(config.getNgramConfig())
                : null;

        // Initialize HantToHansConverter once if needed
        if (config.getExtraConfig().isIgnoreHant()) {
            try {
                this.hantToHansConverter = HantToHansConverter.getInstance();
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize HantToHansConverter", e);
            }
        } else {
            this.hantToHansConverter = null;
        }
    }

    @Override
    public Tokenizer create() {
        // Pass pre-initialized strategies to avoid reconstruction
        return new EsTokTokenizer(config, vocabStrategy, categStrategy, ngramStrategy, hantToHansConverter);
    }
}