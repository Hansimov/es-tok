package org.es.tok.suggest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SemanticArtifactStore implements SemanticExpansionStore {
    private static final String[] RELATION_TYPES = {
        "rewrite",
        "synonym",
        "near_synonym",
        "doc_cooccurrence"
    };
    private static final SemanticArtifactStore INSTANCE = loadDefault();

    private final Map<String, List<SemanticExpansionRule>> expansionsBySurface;
    private final List<String> orderedKeys;
    private final String sourceDescription;

    public static SemanticArtifactStore instance() {
        return INSTANCE;
    }

    public SemanticArtifactStore(Map<String, List<SemanticExpansionRule>> expansionsBySurface, String sourceDescription) {
        this.expansionsBySurface = Map.copyOf(expansionsBySurface);
        this.orderedKeys = this.expansionsBySurface.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
            .toList();
        this.sourceDescription = sourceDescription;
    }

    @Override
    public List<SemanticExpansionRule> expansions(String surface) {
        if (surface == null || surface.isBlank()) {
            return List.of();
        }
        String normalized = SemanticExpansionStore.normalizeSurface(surface);
        List<SemanticExpansionRule> rules = expansionsBySurface.get(normalized);
        if (rules != null) {
            return rules;
        }
        return expansionsBySurface.getOrDefault(compactSurface(normalized), List.of());
    }

    @Override
    public List<String> matchingTerms(String surface) {
        String normalized = SemanticExpansionStore.normalizeSurface(surface);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        String compact = compactSurface(normalized);
        for (String key : orderedKeys) {
            if (normalized.contains(key) || compact.contains(key)) {
                matches.add(key);
            }
        }
        return matches;
    }

    public String sourceDescription() {
        return sourceDescription;
    }

    static SemanticArtifactStore loadDefault() {
        for (Path path : candidateExternalPaths()) {
            if (isReadableBundle(path)) {
                try {
                    return loadFromDirectory(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to load semantic artifacts from " + path, exception);
                }
            }
        }
        try {
            return loadFromResources();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load bundled semantic artifacts", exception);
        }
    }

    static SemanticArtifactStore loadFromDirectory(Path directory) throws IOException {
        Map<String, Map<String, SemanticExpansionRule>> bySurface = new LinkedHashMap<>();
        for (String relationType : RELATION_TYPES) {
            Path path = directory.resolve(relationType + ".tsv");
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                loadRows(bySurface, relationType, reader);
            }
        }
        return finalizeStore(bySurface, directory.toString());
    }

    static SemanticArtifactStore loadFromResources() throws IOException {
        Map<String, Map<String, SemanticExpansionRule>> bySurface = new LinkedHashMap<>();
        for (String relationType : RELATION_TYPES) {
            String resourcePath = "/tuning/semantic/" + relationType + ".tsv";
            try (InputStream inputStream = SemanticArtifactStore.class.getResourceAsStream(resourcePath)) {
                if (inputStream == null) {
                    continue;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    loadRows(bySurface, relationType, reader);
                }
            }
        }
        return finalizeStore(bySurface, "classpath:/tuning/semantic");
    }

    private static SemanticArtifactStore finalizeStore(
        Map<String, Map<String, SemanticExpansionRule>> bySurface,
        String sourceDescription) {
        Map<String, List<SemanticExpansionRule>> finalized = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, SemanticExpansionRule>> entry : bySurface.entrySet()) {
            finalized.put(entry.getKey(), List.copyOf(entry.getValue().values()));
        }
        return new SemanticArtifactStore(finalized, sourceDescription);
    }

    private static List<Path> candidateExternalPaths() {
        List<Path> paths = new ArrayList<>();
        String propertyPath = System.getProperty("es.tok.semantics.path");
        if (propertyPath != null && !propertyPath.isBlank()) {
            paths.add(Path.of(propertyPath));
        }
        String envPath = System.getenv("ES_TOK_SEMANTICS_PATH");
        if (envPath != null && !envPath.isBlank()) {
            paths.add(Path.of(envPath));
        }
        paths.add(Path.of("/usr/share/elasticsearch/plugins/es_tok/semantics/v1/merged"));
        paths.add(Path.of("plugins/es_tok/semantics/v1/merged"));
        paths.add(Path.of("../bili-search-algo/data/semantics/v1/merged"));
        return paths;
    }

    private static boolean isReadableBundle(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        for (String relationType : RELATION_TYPES) {
            if (Files.isRegularFile(directory.resolve(relationType + ".tsv"))) {
                return true;
            }
        }
        return false;
    }

    private static void loadRows(
        Map<String, Map<String, SemanticExpansionRule>> bySurface,
        String relationType,
        BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.strip();
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\t");
            if (parts.length < 3) {
                continue;
            }
            String source = SemanticExpansionStore.normalizeSurface(parts[0]);
            if (source.isBlank()) {
                continue;
            }
            String compactSource = compactSurface(source);
            for (int index = 1; index + 1 < parts.length; index += 2) {
                String target = SemanticExpansionStore.normalizeSurface(parts[index]);
                float weight = parseWeight(parts[index + 1]);
                putExpansion(bySurface, source, target, relationType, weight);
                if (!compactSource.equals(source)) {
                    putExpansion(bySurface, compactSource, target, relationType, weight);
                }
            }
        }
    }

    private static float parseWeight(String value) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException exception) {
            return 0.0f;
        }
    }

    private static String compactSurface(String surface) {
        return surface == null ? "" : surface.replace(" ", "");
    }

    private static void putExpansion(
        Map<String, Map<String, SemanticExpansionRule>> bySurface,
        String source,
        String target,
        String type,
        float weight) {
        if (source.isBlank() || target.isBlank() || source.equals(target) || weight <= 0.0f) {
            return;
        }
        Map<String, SemanticExpansionRule> expansions = bySurface.computeIfAbsent(source, ignored -> new LinkedHashMap<>());
        SemanticExpansionRule existing = expansions.get(target);
        if (existing == null || existing.weight() < weight) {
            expansions.put(target, new SemanticExpansionRule(target, type, weight));
            return;
        }
        if (existing.weight() == weight && existing.type().compareToIgnoreCase(type) > 0) {
            expansions.put(target, new SemanticExpansionRule(target, type, weight));
        }
    }
}