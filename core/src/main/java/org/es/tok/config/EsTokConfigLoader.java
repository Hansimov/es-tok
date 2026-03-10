package org.es.tok.config;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.es.tok.extra.ExtraConfig;
import org.es.tok.extra.ExtraLoader;
import org.es.tok.categ.CategConfig;
import org.es.tok.categ.CategLoader;
import org.es.tok.ngram.NgramConfig;
import org.es.tok.ngram.NgramLoader;
import org.es.tok.vocab.VocabConfig;
import org.es.tok.vocab.VocabLoader;
import org.es.tok.rules.RulesConfig;
import org.es.tok.rules.RulesLoader;

public class EsTokConfigLoader {
    public static EsTokConfig loadConfig(Settings settings, Environment environment) {
        return loadConfig(settings, environment, true); // Enable cache by default for better performance
    }

    public static EsTokConfig loadConfig(Settings settings, Environment environment, boolean useCache) {
        ExtraConfig extraConfig = ExtraLoader.loadExtraConfig(settings);
        CategConfig categConfig = CategLoader.loadCategConfig(settings);
        VocabConfig vocabConfig = VocabLoader.loadVocabConfig(settings, environment, useCache);
        NgramConfig ngramConfig = NgramLoader.loadNgramConfig(settings);
        RulesConfig rulesConfig = RulesLoader.loadRulesConfig(settings);

        return new EsTokConfig(extraConfig, categConfig, vocabConfig, ngramConfig, rulesConfig);
    }
}