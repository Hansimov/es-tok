package org.es.tok.text;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TextQualityRules {
    static final TextQualityRules DEFAULT = loadDefaults();

    private static final String DEFAULT_RESOURCE = "/text/quality_rules.json";

    private final Set<String> noisyAsciiTerms;
    private final Set<String> noisyCjkTerms;
    private final Set<String> noisyTermFragments;
    private final Set<Integer> singleCodePointFunctionWords;
    private final ContextRuleProfile ownerSeedTermsProfile;
    private final ContextRuleProfile associateSeedTermsProfile;
    private final ContextRuleProfile associateCandidatesProfile;

    private TextQualityRules(
            Set<String> noisyAsciiTerms,
            Set<String> noisyCjkTerms,
            Set<String> noisyTermFragments,
            Set<Integer> singleCodePointFunctionWords,
            ContextRuleProfile ownerSeedTermsProfile,
            ContextRuleProfile associateSeedTermsProfile,
            ContextRuleProfile associateCandidatesProfile) {
        this.noisyAsciiTerms = noisyAsciiTerms;
        this.noisyCjkTerms = noisyCjkTerms;
        this.noisyTermFragments = noisyTermFragments;
        this.singleCodePointFunctionWords = singleCodePointFunctionWords;
        this.ownerSeedTermsProfile = ownerSeedTermsProfile;
        this.associateSeedTermsProfile = associateSeedTermsProfile;
        this.associateCandidatesProfile = associateCandidatesProfile;
    }

    boolean isNoisyAsciiTerm(String term) {
        return noisyAsciiTerms.contains(term);
    }

    boolean isNoisyCjkTerm(String term) {
        return noisyCjkTerms.contains(term);
    }

    Set<String> noisyCjkTerms() {
        return noisyCjkTerms;
    }

    Set<String> noisyTermFragments() {
        return noisyTermFragments;
    }

    boolean isFunctionWord(int codePoint) {
        return singleCodePointFunctionWords.contains(codePoint);
    }

    ContextRuleProfile ownerSeedTermsProfile() {
        return ownerSeedTermsProfile;
    }

    ContextRuleProfile associateSeedTermsProfile() {
        return associateSeedTermsProfile;
    }

    ContextRuleProfile associateCandidatesProfile() {
        return associateCandidatesProfile;
    }

    private static TextQualityRules loadDefaults() {
        try (InputStream inputStream = TextQualityRules.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing text quality rules resource: " + DEFAULT_RESOURCE);
            }
            ObjectMapper mapper = new ObjectMapper();
            Map<?, ?> root = mapper.readValue(inputStream, Map.class);
            Map<String, Set<String>> termSets = resolveTermSets(getMap(root, "term_sets"));
            Map<?, ?> profiles = getMap(root, "profiles");
            Set<String> functionWordTerms = termSets.getOrDefault("function_words", Set.of());
            return new TextQualityRules(
                    lowercased(termSets.getOrDefault("noisy_ascii_terms", Set.of())),
                    termSets.getOrDefault("noisy_cjk_terms", Set.of()),
                    termSets.getOrDefault("noisy_term_fragments", Set.of()),
                    toSingleCodePointSet(functionWordTerms),
                    resolveProfile(profiles, "owner_seed_terms", termSets),
                    resolveProfile(profiles, "associate_seed_terms", termSets),
                    resolveProfile(profiles, "associate_candidates", termSets));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load text quality rules from " + DEFAULT_RESOURCE, exception);
        }
    }

    private static Map<String, Set<String>> resolveTermSets(Map<?, ?> termSetsMap) {
        Map<String, Set<String>> resolved = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : termSetsMap.entrySet()) {
            if (entry.getKey() instanceof String key) {
                resolved.put(key, Set.copyOf(getStringList(termSetsMap, key)));
            }
        }
        return resolved;
    }

    private static ContextRuleProfile resolveProfile(Map<?, ?> profiles, String profileName, Map<String, Set<String>> termSets) {
        Map<?, ?> profile = getMap(profiles, profileName);
        return new ContextRuleProfile(
                resolveTermSetRefs(profile, "exclude_exact_term_sets", termSets),
                resolveTermSetRefs(profile, "exclude_contains_term_sets", termSets),
                resolveTermSetRefs(profile, "declude_prefix_term_sets", termSets),
                resolveTermSetRefs(profile, "declude_suffix_term_sets", termSets),
                toSingleCodePointSet(resolveTermSetRefs(profile, "function_word_term_sets", termSets)));
    }

    private static Set<String> resolveTermSetRefs(Map<?, ?> profile, String key, Map<String, Set<String>> termSets) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String termSetName : getStringList(profile, key)) {
            resolved.addAll(termSets.getOrDefault(termSetName, Set.of()));
        }
        return Set.copyOf(resolved);
    }

    private static Set<String> lowercased(Set<String> values) {
        LinkedHashSet<String> lowered = new LinkedHashSet<>();
        for (String value : values) {
            lowered.add(TextNormalization.normalizeLower(value));
        }
        return Set.copyOf(lowered);
    }

    private static Set<Integer> toSingleCodePointSet(Set<String> values) {
        LinkedHashSet<Integer> codePoints = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && value.codePointCount(0, value.length()) == 1) {
                codePoints.add(value.codePointAt(0));
            }
        }
        return Set.copyOf(codePoints);
    }

    private static Map<?, ?> getMap(Map<?, ?> source, String key) {
        Object value = source == null ? null : source.get(key);
        if (value instanceof Map<?, ?> mapValue) {
            return mapValue;
        }
        return Map.of();
    }

    private static List<String> getStringList(Map<?, ?> source, String key) {
        Object value = source == null ? null : source.get(key);
        if (!(value instanceof List<?> listValue)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (Object item : listValue) {
            if (item instanceof String stringValue && !stringValue.isBlank()) {
                strings.add(stringValue);
            }
        }
        return List.copyOf(strings);
    }

    record ContextRuleProfile(
            Set<String> excludeExactTerms,
            Set<String> excludeContainsTerms,
            Set<String> decludePrefixes,
            Set<String> decludeSuffixes,
            Set<Integer> functionWordCodePoints) {
    }
}