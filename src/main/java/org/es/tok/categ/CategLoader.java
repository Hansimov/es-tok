package org.es.tok.categ;

import org.elasticsearch.common.settings.Settings;

public class CategLoader {
    public static CategConfig loadCategConfig(Settings settings) {
        boolean useCateg = settings.getAsBoolean("use_categ", false);
        boolean splitWord = settings.getAsBoolean("split_word", true);
        
        return new CategConfig(useCateg, splitWord);
    }
}