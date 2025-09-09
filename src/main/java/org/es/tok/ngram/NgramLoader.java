package org.es.tok.ngram;

import org.elasticsearch.common.settings.Settings;

public class NgramLoader {
    public static NgramConfig loadNgramConfig(Settings settings) {
        boolean useNgram = settings.getAsBoolean("use_ngram", false);
        if (!useNgram) {
            return new NgramConfig(false, false, false, false, true);
        }

        Settings ngramConfig = settings.getAsSettings("ngram_config");
        if (ngramConfig == null || ngramConfig.isEmpty()) {
            // Default to all false if use_ngram is true but no ngram_config provided
            return new NgramConfig(true, false, false, false, true);
        }

        boolean useBigram = ngramConfig.getAsBoolean("use_bigram", false);
        boolean useVcgram = ngramConfig.getAsBoolean("use_vcgram", false);
        boolean useVbgram = ngramConfig.getAsBoolean("use_vbgram", false);
        boolean dropCogram = ngramConfig.getAsBoolean("drop_cogram", true);

        return new NgramConfig(true, useBigram, useVcgram, useVbgram, dropCogram);
    }
}