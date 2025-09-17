package org.es.tok.categ;

import org.elasticsearch.common.settings.Settings;

public class CategLoader {
    public static CategConfig loadCategConfig(Settings settings) {
        boolean useCateg = settings.getAsBoolean("use_categ", true); // Default to true for fallback
        if (!useCateg) {
            return new CategConfig(false, false);
        }
        Settings categConfig = settings.getAsSettings("categ_config");
        if (categConfig == null || categConfig.isEmpty()) {
            return new CategConfig(useCateg, false);
        }
        boolean splitWord = categConfig.getAsBoolean("split_word", false);
        return new CategConfig(useCateg, splitWord);
    }
}