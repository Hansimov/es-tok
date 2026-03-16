package org.es.tok.core.payload;

import org.elasticsearch.common.settings.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SettingsFlattener {
    private SettingsFlattener() {
    }

    public static Settings flatten(Map<String, Object> payload) {
        Settings.Builder builder = Settings.builder();
        flattenInto(builder, null, payload);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    public static void flattenInto(Settings.Builder builder, String prefix, Map<String, Object> payload) {
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if ("text".equals(entry.getKey())) {
                continue;
            }
            String key = prefix == null ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flattenInto(builder, key, (Map<String, Object>) nested);
                continue;
            }
            if (value instanceof List<?> list) {
                List<String> values = new ArrayList<>();
                for (Object item : list) {
                    values.add(String.valueOf(item));
                }
                builder.putList(key, values);
                continue;
            }
            if (value != null) {
                builder.put(key, String.valueOf(value));
            }
        }
    }
}