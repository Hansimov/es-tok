package org.es.tok.suggest;

import org.es.tok.text.TextNormalization;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public interface SemanticExpansionStore {
    String TSV_SPACE_MASK = "▂";

    List<SemanticExpansionRule> expansions(String surface);

    List<String> matchingTerms(String surface);

    static String normalizeSurface(String surface) {
        if (surface == null || surface.isBlank()) {
            return "";
        }
        surface = surface.replace(TSV_SPACE_MASK, " ");
        List<String> normalizedParts = new ArrayList<>();
        for (String rawPart : surface.trim().split("\\s+")) {
            String normalized = TextNormalization.normalizeAnalyzedToken(rawPart);
            if (!normalized.isBlank()) {
                normalizedParts.add(normalized);
            }
        }
        return String.join(" ", normalizedParts);
    }

    record SemanticExpansionRule(String text, String type, float weight) {
        public SemanticExpansionRule {
            text = SemanticExpansionStore.normalizeSurface(text);
            type = type == null || type.isBlank() ? "synonym" : type.toLowerCase(Locale.ROOT);
            weight = Math.max(0.0f, weight);
        }
    }
}