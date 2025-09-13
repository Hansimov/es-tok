package org.es.tok.config;

import org.es.tok.extra.ExtraConfig;
import org.es.tok.categ.CategConfig;
import org.es.tok.ngram.NgramConfig;
import org.es.tok.vocab.VocabConfig;

public class EsTokConfig {
    private final VocabConfig vocabConfig;
    private final CategConfig categConfig;
    private final NgramConfig ngramConfig;
    private final ExtraConfig extraConfig;

    public EsTokConfig(VocabConfig vocabConfig, CategConfig categConfig, NgramConfig ngramConfig,
            ExtraConfig extraConfig) {
        this.extraConfig = extraConfig;
        this.categConfig = categConfig;
        this.vocabConfig = vocabConfig;
        this.ngramConfig = ngramConfig;
    }

    // Backward compatibility constructor
    public EsTokConfig(VocabConfig vocabConfig, CategConfig categConfig, NgramConfig ngramConfig, boolean ignoreCase,
            boolean dropDuplicates) {
        this.vocabConfig = vocabConfig;
        this.categConfig = categConfig;
        this.ngramConfig = ngramConfig;
        this.extraConfig = new ExtraConfig(ignoreCase, dropDuplicates);
    }

    public ExtraConfig getExtraConfig() {
        return extraConfig;
    }

    public CategConfig getCategConfig() {
        return categConfig;
    }

    public VocabConfig getVocabConfig() {
        return vocabConfig;
    }

    public NgramConfig getNgramConfig() {
        return ngramConfig;
    }

    @Override
    public String toString() {
        return String.format(
                "EsTokConfig{vocabConfig=%s, categConfig=%s, ngramConfig=%s, extraConfig=%s}",
                vocabConfig, categConfig, ngramConfig, extraConfig);
    }
}