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

    private final List<CompoundAbbreviationRule> compoundAbbreviationRules;
    private final List<SuffixAliasRule> suffixAliases;

    private RelatedOwnerTopicVariantRules(
            List<CompoundAbbreviationRule> compoundAbbreviationRules,
            List<SuffixAliasRule> suffixAliases) {
        this.compoundAbbreviationRules = List.copyOf(compoundAbbreviationRules);
        this.suffixAliases = List.copyOf(suffixAliases);
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
        for (CompoundAbbreviationRule rule : compoundAbbreviationRules) {
            String variant = rule.apply(normalized);
            if (variant != null && !variant.isBlank() && !variant.equals(normalized)) {
                variants.add(variant);
            }
        }
        for (SuffixAliasRule rule : suffixAliases) {
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
            List<CompoundAbbreviationRule> compoundAbbreviations = new ArrayList<>();
            List<SuffixAliasRule> suffixAliases = new ArrayList<>();
            for (Object item : getList(root, "compound_abbreviation_rules")) {
                if (item instanceof Map<?, ?> map) {
                    CompoundAbbreviationRule rule = CompoundAbbreviationRule.fromMap(map);
                    if (rule != null) {
                        compoundAbbreviations.add(rule);
                    }
                }
            }
            for (Object item : getList(root, "suffix_aliases")) {
                if (item instanceof Map<?, ?> map) {
                    SuffixAliasRule rule = SuffixAliasRule.fromMap(map);
                    if (rule != null) {
                        suffixAliases.add(rule);
                    }
                }
            }
            return new RelatedOwnerTopicVariantRules(compoundAbbreviations, suffixAliases);
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

    private static List<Integer> intList(Map<?, ?> source, String key) {
        List<Integer> values = new ArrayList<>();
        for (Object item : getList(source, key)) {
            if (item instanceof Number number) {
                values.add(number.intValue());
                continue;
            }
            if (item == null) {
                continue;
            }
            try {
                values.add(Integer.parseInt(item.toString()));
            } catch (NumberFormatException ignored) {
                // Ignore malformed tuning entries.
            }
        }
        return List.copyOf(values);
    }

    private record CompoundAbbreviationRule(
            int sourceHanCodePoints,
            List<Integer> pickCodePointIndexes,
            boolean allowEmptySuffix,
            int maxSuffixCodePoints,
            boolean allowAsciiDigitSuffix,
            Set<Integer> allowedHanSuffixes) {

        static CompoundAbbreviationRule fromMap(Map<?, ?> source) {
            int sourceHanCodePoints = Math.max(0, intValue(source.get("source_han_codepoints"), 0));
            List<Integer> pickIndexes = intList(source, "pick_codepoint_indexes");
            if (sourceHanCodePoints <= 0 || pickIndexes.isEmpty()) {
                return null;
            }
            boolean allowEmptySuffix = Boolean.TRUE.equals(source.get("allow_empty_suffix"));
            return new CompoundAbbreviationRule(
                    sourceHanCodePoints,
                    pickIndexes,
                    allowEmptySuffix,
                    Math.max(0, intValue(source.get("max_suffix_codepoints"), 0)),
                    Boolean.TRUE.equals(source.get("allow_ascii_digit_suffix")),
                    singleCodePointSet(source, "allowed_han_suffixes"));
        }

        String apply(String normalizedText) {
            List<Integer> codePoints = normalizedText.codePoints().boxed().toList();
            if (codePoints.size() < sourceHanCodePoints) {
                return null;
            }
            List<Integer> sourceCodePoints = codePoints.subList(0, sourceHanCodePoints);
            if (!sourceCodePoints.stream().allMatch(this::isHan)) {
                return null;
            }
            String suffix = new String(
                    codePoints.subList(sourceHanCodePoints, codePoints.size()).stream()
                            .mapToInt(Integer::intValue)
                            .toArray(),
                    0,
                    Math.max(0, codePoints.size() - sourceHanCodePoints));
            if (suffix.isEmpty()) {
                return allowEmptySuffix ? abbreviation(sourceCodePoints) : null;
            }
            if (!suffixAllowed(suffix)) {
                return null;
            }
            return abbreviation(sourceCodePoints) + suffix;
        }

        private String abbreviation(List<Integer> sourceCodePoints) {
            int[] abbreviationCodePoints = pickCodePointIndexes.stream()
                    .filter(index -> index >= 0 && index < sourceCodePoints.size())
                    .map(sourceCodePoints::get)
                    .mapToInt(Integer::intValue)
                    .toArray();
            return new String(abbreviationCodePoints, 0, abbreviationCodePoints.length);
        }

        private boolean suffixAllowed(String suffix) {
            if (suffix.codePointCount(0, suffix.length()) > maxSuffixCodePoints) {
                return false;
            }
            return suffix.codePoints().allMatch(codePoint -> {
                if (isHan(codePoint)) {
                    return allowedHanSuffixes.contains(codePoint);
                }
                return allowAsciiDigitSuffix && Character.isLetterOrDigit(codePoint) && codePoint < 128;
            });
        }

        private boolean isHan(int codePoint) {
            return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
        }

        private static int intValue(Object value, int defaultValue) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
    }

    private record SuffixAliasRule(
            String sourceSuffix,
            String targetSuffix,
            int minPrefixCodePoints) {

        static SuffixAliasRule fromMap(Map<?, ?> source) {
            String sourceSuffix = normalizeString(source.get("source_suffix"));
            String targetSuffix = normalizeString(source.get("target_suffix"));
            if (sourceSuffix.isBlank() || targetSuffix.isBlank()) {
                return null;
            }
            return new SuffixAliasRule(
                    sourceSuffix,
                    targetSuffix,
                    Math.max(0, intValue(source.get("min_prefix_codepoints"), 0)));
        }

        String apply(String normalizedText) {
            if (!normalizedText.endsWith(sourceSuffix)) {
                return null;
            }
            String prefix = normalizedText.substring(0, normalizedText.length() - sourceSuffix.length());
            if (prefix.codePointCount(0, prefix.length()) < minPrefixCodePoints) {
                return null;
            }
            return prefix + targetSuffix;
        }

        private static String normalizeString(Object value) {
            return TextNormalization.normalizeLower(String.valueOf(value == null ? "" : value))
                    .replaceAll("\\s+", "");
        }

        private static int intValue(Object value, int defaultValue) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
    }
}
