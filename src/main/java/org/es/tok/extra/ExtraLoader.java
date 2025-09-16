package org.es.tok.extra;

import org.elasticsearch.common.settings.Settings;

public class ExtraLoader {
    public static ExtraConfig loadExtraConfig(Settings settings) {
        Settings extraConfig = settings.getAsSettings("extra_config");

        boolean ignoreCase;
        boolean ignoreHant;
        boolean dropDuplicates;

        if (extraConfig != null && !extraConfig.isEmpty()) {
            ignoreCase = extraConfig.getAsBoolean("ignore_case", true);
            ignoreHant = extraConfig.getAsBoolean("ignore_hant", true);
            dropDuplicates = extraConfig.getAsBoolean("drop_duplicates", true);
        } else {
            ignoreCase = settings.getAsBoolean("ignore_case", true);
            ignoreHant = settings.getAsBoolean("ignore_hant", true);
            dropDuplicates = settings.getAsBoolean("drop_duplicates", true);
        }

        return new ExtraConfig(ignoreCase, ignoreHant, dropDuplicates);
    }
}
