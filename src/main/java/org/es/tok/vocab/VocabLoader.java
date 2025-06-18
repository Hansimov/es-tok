package org.es.tok.vocab;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;

import java.util.List;

public class VocabLoader {
    public static VocabConfig loadVocabConfig(Settings settings, Environment environment) {
        return loadVocabConfig(settings, environment, false);
    }

    public static VocabConfig loadVocabConfig(Settings settings, Environment environment, boolean useCache) {
        boolean useVocab = settings.getAsBoolean("use_vocab", true);

        List<String> vocabs = VocabFileLoader.loadVocabs(settings, environment, useCache);

        return new VocabConfig(useVocab, vocabs);
    }
}