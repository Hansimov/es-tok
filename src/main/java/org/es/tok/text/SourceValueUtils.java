package org.es.tok.text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class SourceValueUtils {
    public static List<String> flattenStringValues(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String stringValue) {
            return stringValue.isBlank() ? List.of() : List.of(stringValue);
        }
        if (value instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>();
            for (Object item : collection) {
                values.addAll(flattenStringValues(item));
            }
            return values;
        }
        String stringValue = value.toString();
        return stringValue.isBlank() ? List.of() : List.of(stringValue);
    }

    private SourceValueUtils() {
    }
}