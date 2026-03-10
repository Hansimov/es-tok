package org.es.tok.config;

import org.es.tok.extra.ExtraConfig;
import org.es.tok.categ.CategConfig;
import org.es.tok.ngram.NgramConfig;
import org.es.tok.vocab.VocabConfig;
import org.es.tok.rules.RulesConfig;

public class EsTokConfig {
    private final ExtraConfig extraConfig;
    private final CategConfig categConfig;
    private final VocabConfig vocabConfig;
    private final NgramConfig ngramConfig;
    private final RulesConfig rulesConfig;

    public EsTokConfig(ExtraConfig extraConfig, CategConfig categConfig, VocabConfig vocabConfig,
            NgramConfig ngramConfig, RulesConfig rulesConfig) {
        this.extraConfig = extraConfig;
        this.categConfig = categConfig;
        this.vocabConfig = vocabConfig;
        this.ngramConfig = ngramConfig;
        this.rulesConfig = rulesConfig;
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

    public RulesConfig getRulesConfig() {
        return rulesConfig;
    }

    @Override
    public String toString() {
        return String.format(
                "EsTokConfig{vocabConfig=%s, categConfig=%s, ngramConfig=%s, extraConfig=%s, rulesConfig=%s}",
                vocabConfig, categConfig, ngramConfig, extraConfig, rulesConfig);
    }
}