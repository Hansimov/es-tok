package org.es.tok.suggest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.es.tok.text.TextNormalization;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class QueryExpansionTuning {
    private static final QueryExpansionTuning INSTANCE = load();
    private static final float DEFAULT_SYNONYM_WEIGHT = 0.92f;
    private static final float DEFAULT_NEAR_SYNONYM_WEIGHT = 0.78f;

    private final Map<String, List<ExpansionRule>> expansionsBySurface;
    private final List<String> orderedKeys;

    static QueryExpansionTuning instance() {
        return INSTANCE;
    }

    List<ExpansionRule> expansions(String surface) {
        if (surface == null || surface.isBlank()) {
            return List.of();
        }
        return expansionsBySurface.getOrDefault(normalizeSurface(surface), List.of());
    }

    List<String> matchingTerms(String surface) {
        String normalized = normalizeSurface(surface);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String key : orderedKeys) {
            if (normalized.contains(key)) {
                matches.add(key);
            }
        }
        return matches;
    }

    static String normalizeSurface(String surface) {
        if (surface == null || surface.isBlank()) {
            return "";
        }
        List<String> normalizedParts = new ArrayList<>();
        for (String rawPart : surface.trim().split("\\s+")) {
            String normalized = TextNormalization.normalizeAnalyzedToken(rawPart);
            if (!normalized.isBlank()) {
                normalizedParts.add(normalized);
            }
        }
        return String.join(" ", normalizedParts);
    }

    private QueryExpansionTuning(Map<String, List<ExpansionRule>> expansionsBySurface) {
        this.expansionsBySurface = expansionsBySurface;
        this.orderedKeys = expansionsBySurface.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
            .toList();
    }

    @SuppressWarnings("unchecked")
    private static QueryExpansionTuning load() {
        try (InputStream inputStream = QueryExpansionTuning.class.getResourceAsStream("/tuning/query_expansion_tuning.json")) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing query expansion tuning resource");
            }

            Map<String, Object> root = new ObjectMapper().readValue(inputStream, Map.class);
            Map<String, Map<String, ExpansionRule>> bySurface = new LinkedHashMap<>();
            addRewriteRules(bySurface, (Map<String, Object>) root.get("rewrites"));
            addGroupRules(bySurface, (List<Object>) root.get("synonym_groups"), "synonym", DEFAULT_SYNONYM_WEIGHT);
            addWeightedGroupRules(bySurface, (List<Object>) root.get("near_synonym_groups"), "near_synonym", DEFAULT_NEAR_SYNONYM_WEIGHT);

            Map<String, List<ExpansionRule>> finalized = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, ExpansionRule>> entry : bySurface.entrySet()) {
                finalized.put(entry.getKey(), List.copyOf(entry.getValue().values()));
            }
            return new QueryExpansionTuning(Map.copyOf(finalized));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load query expansion tuning resource", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static void addWeightedGroupRules(
        Map<String, Map<String, ExpansionRule>> bySurface,
        List<Object> groups,
        String type,
        float defaultWeight) {
        if (groups == null) {
            return;
        }
        for (Object rawGroup : groups) {
            if (!(rawGroup instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Object rawTerms = rawMap.get("terms");
            if (!(rawTerms instanceof Collection<?> collection)) {
                continue;
            }
            float weight = rawMap.get("weight") instanceof Number number ? number.floatValue() : defaultWeight;
            addGroupRules(bySurface, List.of(new ArrayList<>(collection)), type, weight);
        }
    }

    private static void addRewriteRules(Map<String, Map<String, ExpansionRule>> bySurface, Map<String, Object> rewrites) {
        if (rewrites == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : rewrites.entrySet()) {
            String source = normalizeSurface(entry.getKey());
            if (source.isBlank()) {
                continue;
            }
            for (String target : normalizeTerms(entry.getValue())) {
                putExpansion(bySurface, source, target, "rewrite", 1.0f);
            }
        }
    }

    private static void addGroupRules(
        Map<String, Map<String, ExpansionRule>> bySurface,
        List<Object> groups,
        String type,
        float weight) {
        if (groups == null) {
            return;
        }
        for (Object rawGroup : groups) {
            List<String> terms = normalizeTerms(rawGroup);
            for (String source : terms) {
                for (String target : terms) {
                    if (!source.equals(target)) {
                        putExpansion(bySurface, source, target, type, weight);
                    }
                }
            }
        }
    }

    private static List<String> normalizeTerms(Object rawTerms) {
        if (rawTerms == null) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        if (rawTerms instanceof Collection<?> collection) {
            for (Object rawValue : collection) {
                String normalized = normalizeSurface(rawValue == null ? "" : rawValue.toString());
                if (!normalized.isBlank()) {
                    terms.add(normalized);
                }
            }
        } else {
            String normalized = normalizeSurface(rawTerms.toString());
            if (!normalized.isBlank()) {
                terms.add(normalized);
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(terms));
    }

    private static void putExpansion(
        Map<String, Map<String, ExpansionRule>> bySurface,
        String source,
        String target,
        String type,
        float weight) {
        if (source.isBlank() || target.isBlank() || source.equals(target)) {
            return;
        }
        Map<String, ExpansionRule> expansions = bySurface.computeIfAbsent(source, ignored -> new LinkedHashMap<>());
        ExpansionRule existing = expansions.get(target);
        if (existing == null || existing.weight() < weight) {
            expansions.put(target, new ExpansionRule(target, type, weight));
            return;
        }
        if (existing.weight() == weight && existing.type().compareToIgnoreCase(type) > 0) {
            expansions.put(target, new ExpansionRule(target, type, weight));
        }
    }

    record ExpansionRule(String text, String type, float weight) {
        ExpansionRule {
            text = normalizeSurface(text);
            type = type == null ? "synonym" : type.toLowerCase(Locale.ROOT);
        }
    }
}