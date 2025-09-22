package org.es.tok.extra;

import org.elasticsearch.common.settings.Settings;

public class ExtraLoader {
    public static ExtraConfig loadExtraConfig(Settings settings) {
        Settings extraConfig = settings.getAsSettings("extra_config");

        boolean ignoreCase;
        boolean ignoreHant;
        boolean dropDuplicates;
        boolean dropCategs;

        if (extraConfig != null && !extraConfig.isEmpty()) {
            ignoreCase = extraConfig.getAsBoolean("ignore_case", true);
            ignoreHant = extraConfig.getAsBoolean("ignore_hant", true);
            dropDuplicates = extraConfig.getAsBoolean("drop_duplicates", true);
            dropCategs = extraConfig.getAsBoolean("drop_categs", false);
        } else {
            ignoreCase = settings.getAsBoolean("ignore_case", true);
            ignoreHant = settings.getAsBoolean("ignore_hant", true);
            dropDuplicates = settings.getAsBoolean("drop_duplicates", true);
            dropCategs = settings.getAsBoolean("drop_categs", false);
        }

        return new ExtraConfig(ignoreCase, ignoreHant, dropDuplicates, dropCategs);
    }
}
