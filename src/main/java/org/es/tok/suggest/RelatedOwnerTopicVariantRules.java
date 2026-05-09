package org.es.tok.suggest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.es.tok.text.TextNormalization;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RelatedOwnerTopicVariantRules {
    private static final String DEFAULT_RESOURCE = "/tuning/related_owner_topic_variants.json";
    private static final RelatedOwnerTopicVariantRules DEFAULT = loadDefaults();

    private final List<PrefixAliasRule> prefixAliases;

    private RelatedOwnerTopicVariantRules(List<PrefixAliasRule> prefixAliases) {
        this.prefixAliases = List.copyOf(prefixAliases);
    }

    static List<String> variantsFor(String text) {
        return DEFAULT.expand(text);
    }

    private List<String> expand(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = TextNormalization.normalizeLower(text).replaceAll("\\s+", "");
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        for (PrefixAliasRule rule : prefixAliases) {
            String variant = rule.apply(normalized);
            if (variant != null && !variant.isBlank() && !variant.equals(normalized)) {
                variants.add(variant);
            }
        }
        return List.copyOf(variants);
    }

    private static RelatedOwnerTopicVariantRules loadDefaults() {
        try (InputStream inputStream = RelatedOwnerTopicVariantRules.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing related owner topic variant resource: " + DEFAULT_RESOURCE);
            }
            Map<?, ?> root = new ObjectMapper().readValue(inputStream, Map.class);
            List<PrefixAliasRule> aliases = new ArrayList<>();
            for (Object item : getList(root, "prefix_aliases")) {
                if (item instanceof Map<?, ?> map) {
                    PrefixAliasRule rule = PrefixAliasRule.fromMap(map);
                    if (rule != null) {
                        aliases.add(rule);
                    }
                }
            }
            return new RelatedOwnerTopicVariantRules(aliases);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load related owner topic variants", exception);
        }
    }

    private static List<?> getList(Map<?, ?> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value instanceof List<?> list ? list : List.of();
    }

    private static Set<Integer> singleCodePointSet(Map<?, ?> source, String key) {
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        for (Object item : getList(source, key)) {
            if (!(item instanceof String text) || text.isBlank()) {
                continue;
            }
            String normalized = TextNormalization.normalizeLower(text);
            if (normalized.codePointCount(0, normalized.length()) == 1) {
                values.add(normalized.codePointAt(0));
            }
        }
        return Set.copyOf(values);
    }

    private record PrefixAliasRule(
            String sourcePrefix,
            String targetPrefix,
            boolean allowEmptySuffix,
            Set<Integer> allowedHanSuffixes) {

        static PrefixAliasRule fromMap(Map<?, ?> source) {
            String sourcePrefix = normalizeString(source.get("source_prefix"));
            String targetPrefix = normalizeString(source.get("target_prefix"));
            if (sourcePrefix.isBlank() || targetPrefix.isBlank()) {
                return null;
            }
            boolean allowEmptySuffix = Boolean.TRUE.equals(source.get("allow_empty_suffix"));
            return new PrefixAliasRule(
                    sourcePrefix,
                    targetPrefix,
                    allowEmptySuffix,
                    singleCodePointSet(source, "allowed_han_suffixes"));
        }

        String apply(String normalizedText) {
            if (!normalizedText.startsWith(sourcePrefix)) {
                return null;
            }
            String suffix = normalizedText.substring(sourcePrefix.length());
            if (suffix.isEmpty()) {
                return allowEmptySuffix ? targetPrefix : null;
            }
            if (!suffixAllowed(suffix)) {
                return null;
            }
            return targetPrefix + suffix;
        }

        private boolean suffixAllowed(String suffix) {
            return suffix.codePoints().allMatch(codePoint ->
                    Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN
                            || allowedHanSuffixes.contains(codePoint));
        }

        private static String normalizeString(Object value) {
            return TextNormalization.normalizeLower(String.valueOf(value == null ? "" : value))
                    .replaceAll("\\s+", "");
        }
    }
}
