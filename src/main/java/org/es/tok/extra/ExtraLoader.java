package org.es.tok.extra;

import org.elasticsearch.common.settings.Settings;

public class ExtraLoader {
    public static ExtraConfig loadExtraConfig(Settings settings) {
        Settings extraConfig = settings.getAsSettings("extra_config");

        boolean ignoreCase;
        boolean dropDuplicates;

        if (extraConfig != null && !extraConfig.isEmpty()) {
            ignoreCase = extraConfig.getAsBoolean("ignore_case", true);
            dropDuplicates = extraConfig.getAsBoolean("drop_duplicates", false);
        } else {
            ignoreCase = settings.getAsBoolean("ignore_case", true);
            dropDuplicates = settings.getAsBoolean("drop_duplicates", true);
        }

        return new ExtraConfig(ignoreCase, dropDuplicates);
    }
}
