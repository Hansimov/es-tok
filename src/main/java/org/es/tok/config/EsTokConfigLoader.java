package org.es.tok.config;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.es.tok.categ.CategConfig;
import org.es.tok.categ.CategLoader;
import org.es.tok.ngram.NgramConfig;
import org.es.tok.ngram.NgramLoader;
import org.es.tok.vocab.VocabConfig;
import org.es.tok.vocab.VocabLoader;

public class EsTokConfigLoader {
    public static EsTokConfig loadConfig(Settings settings, Environment environment) {
        return loadConfig(settings, environment, false);
    }

    public static EsTokConfig loadConfig(Settings settings, Environment environment, boolean useCache) {
        VocabConfig vocabConfig = VocabLoader.loadVocabConfig(settings, environment, useCache);
        CategConfig categConfig = CategLoader.loadCategConfig(settings);
        NgramConfig ngramConfig = NgramLoader.loadNgramConfig(settings);
        boolean ignoreCase = settings.getAsBoolean("ignore_case", true);
        boolean dropDuplicates = settings.getAsBoolean("drop_duplicates", false);

        return new EsTokConfig(vocabConfig, categConfig, ngramConfig, ignoreCase, dropDuplicates);
    }
}