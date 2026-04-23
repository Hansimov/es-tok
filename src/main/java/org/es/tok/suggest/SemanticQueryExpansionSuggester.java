package org.es.tok.suggest;

import org.es.tok.suggest.LuceneIndexSuggester.SuggestionOption;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class SemanticQueryExpansionSuggester {
    private final QueryExpansionTuning tuning;

    public SemanticQueryExpansionSuggester() {
        this(QueryExpansionTuning.instance());
    }

    SemanticQueryExpansionSuggester(QueryExpansionTuning tuning) {
        this.tuning = tuning;
    }

    public List<SuggestionOption> suggest(String text, int size) {
        String normalizedSurface = QueryExpansionTuning.normalizeSurface(text);
        if (normalizedSurface.isBlank() || size <= 0) {
            return List.of();
        }

        Map<String, ExpansionAccumulator> candidates = new HashMap<>();
        addDirectExpansions(candidates, normalizedSurface, 1.0f);
        addContainedSurfaceExpansions(candidates, normalizedSurface, 0.94f);

        List<String> parts = splitSurface(normalizedSurface);
        if (parts.size() > 1) {
            for (int index = 0; index < parts.size(); index++) {
                String part = parts.get(index);
                for (QueryExpansionTuning.ExpansionRule rule : tuning.expansions(part)) {
                    List<String> replaced = new ArrayList<>(parts);
                    replaced.set(index, rule.text());
                    addCandidate(candidates, String.join(" ", replaced), rule.type(), rule.weight() * 0.98f);
                }
            }
        }

        return candidates.values().stream()
            .sorted(ExpansionAccumulator.ORDER)
            .limit(size)
            .map(ExpansionAccumulator::toSuggestion)
            .toList();
    }

    private void addDirectExpansions(
        Map<String, ExpansionAccumulator> candidates,
        String normalizedSurface,
        float boost) {
        for (QueryExpansionTuning.ExpansionRule rule : tuning.expansions(normalizedSurface)) {
            addCandidate(candidates, rule.text(), rule.type(), rule.weight() * boost);
        }
    }

    private void addContainedSurfaceExpansions(
        Map<String, ExpansionAccumulator> candidates,
        String normalizedSurface,
        float boost) {
        for (String matchedTerm : tuning.matchingTerms(normalizedSurface)) {
            if (matchedTerm.equals(normalizedSurface)) {
                continue;
            }
            for (QueryExpansionTuning.ExpansionRule rule : tuning.expansions(matchedTerm)) {
                String replaced = replaceSurface(normalizedSurface, matchedTerm, rule.text());
                addCandidate(candidates, replaced, rule.type(), rule.weight() * boost);
            }
        }
    }

    private static void addCandidate(
        Map<String, ExpansionAccumulator> candidates,
        String candidateText,
        String type,
        float weight) {
        String normalizedCandidate = QueryExpansionTuning.normalizeSurface(candidateText);
        if (normalizedCandidate.isBlank() || weight <= 0.0f) {
            return;
        }
        candidates.computeIfAbsent(normalizedCandidate, ExpansionAccumulator::new)
            .add(type, weight);
    }

    private static List<String> splitSurface(String normalizedSurface) {
        if (normalizedSurface.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : normalizedSurface.split(" ")) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static String replaceSurface(String surface, String source, String target) {
        if (surface.indexOf(' ') >= 0) {
            List<String> parts = splitSurface(surface);
            boolean changed = false;
            for (int index = 0; index < parts.size(); index++) {
                if (parts.get(index).equals(source)) {
                    parts.set(index, target);
                    changed = true;
                }
            }
            if (changed) {
                return String.join(" ", parts);
            }
        }
        return surface.replace(source, target);
    }

    private static final class ExpansionAccumulator {
        private static final Comparator<ExpansionAccumulator> ORDER = Comparator
            .comparingDouble(ExpansionAccumulator::score).reversed()
            .thenComparing(ExpansionAccumulator::text);

        private final String text;
        private final LinkedHashSet<String> types = new LinkedHashSet<>();
        private final Map<String, Float> typeScores = new HashMap<>();
        private float score;

        private ExpansionAccumulator(String text) {
            this.text = text;
        }

        private void add(String type, float weight) {
            float branchScore = (weight * 100.0f) + Math.min(12.0f, text.length() * 0.2f);
            this.score += branchScore;
            this.types.add(type);
            this.typeScores.merge(type, branchScore, Float::sum);
        }

        private float score() {
            return score + Math.max(0, types.size() - 1) * 4.0f;
        }

        private String text() {
            return text;
        }

        private String dominantType() {
            return typeScores.entrySet().stream()
                .max(Map.Entry.<String, Float>comparingByValue().thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElse("synonym");
        }

        private SuggestionOption toSuggestion() {
            return new SuggestionOption(text, 1, score(), dominantType());
        }
    }
}