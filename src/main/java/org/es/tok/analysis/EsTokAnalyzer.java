package org.es.tok.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.es.tok.config.EsTokConfig;
import org.es.tok.tokenize.EsTokTokenizer;
import org.es.tok.strategy.VocabStrategy;
import org.es.tok.strategy.CategStrategy;
import org.es.tok.strategy.NgramStrategy;
import org.es.tok.extra.HantToHansConverter;

public class EsTokAnalyzer extends Analyzer {
    private final EsTokConfig config;
    private final VocabStrategy vocabStrategy;
    private final CategStrategy categStrategy;
    private final NgramStrategy ngramStrategy;
    private final HantToHansConverter hantToHansConverter;

    // Constructor for pre-initialized strategies (recommended for production)
    public EsTokAnalyzer(EsTokConfig config, VocabStrategy vocabStrategy, CategStrategy categStrategy,
            NgramStrategy ngramStrategy, HantToHansConverter hantToHansConverter) {
        this.config = config;
        this.vocabStrategy = vocabStrategy;
        this.categStrategy = categStrategy;
        this.ngramStrategy = ngramStrategy;
        this.hantToHansConverter = hantToHansConverter;
    }

    // Constructor that auto-initializes strategies (used in tests and REST handler)
    public EsTokAnalyzer(EsTokConfig config) {
        this.config = config;

        // Pre-initialize strategies to avoid repeated construction
        this.vocabStrategy = config.getVocabConfig().isUseVocab()
                ? new VocabStrategy(config.getVocabConfig().getVocabs())
                : null;
        this.categStrategy = config.getCategConfig().isUseCateg()
                ? new CategStrategy(config.getCategConfig().isSplitWord())
                : null;
        this.ngramStrategy = config.getNgramConfig().hasAnyNgramEnabled()
                ? new NgramStrategy(config.getNgramConfig())
                : null;

        // Initialize HantToHansConverter if needed
        HantToHansConverter converter = null;
        if (config.getExtraConfig().isIgnoreHant()) {
            try {
                converter = HantToHansConverter.getInstance();
            } catch (Exception e) {
                // In test/development environments, disable the feature if it fails
                System.err.println(
                        "Warning: HantToHansConverter initialization failed, disabling traditional Chinese conversion: "
                                + e.getMessage());
                // Leave converter as null, the tokenizer will handle this gracefully
            }
        }
        this.hantToHansConverter = converter;
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        // Use the performance-optimized constructor with pre-built strategies
        EsTokTokenizer tokenizer = new EsTokTokenizer(config, vocabStrategy, categStrategy, ngramStrategy,
                hantToHansConverter);
        return new TokenStreamComponents(tokenizer);
    }

    @Override
    public String toString() {
        return String.format("EsTokAnalyzer{config=%s}", config);
    }
}