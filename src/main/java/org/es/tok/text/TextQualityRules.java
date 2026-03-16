package org.es.tok.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

final class TextQualityRules {
    static final TextQualityRules DEFAULT = loadDefaults();

    private final Set<String> noisyAsciiTerms;
    private final Set<String> noisyCjkTerms;
    private final Set<String> noisyTermFragments;
    private final Set<Integer> functionWords;

    private TextQualityRules(
            Set<String> noisyAsciiTerms,
            Set<String> noisyCjkTerms,
            Set<String> noisyTermFragments,
            Set<Integer> functionWords) {
        this.noisyAsciiTerms = noisyAsciiTerms;
        this.noisyCjkTerms = noisyCjkTerms;
        this.noisyTermFragments = noisyTermFragments;
        this.functionWords = functionWords;
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
        return functionWords.contains(codePoint);
    }

    private static TextQualityRules loadDefaults() {
        return new TextQualityRules(
                loadLowercasedTerms("/text/noisy_ascii_terms.txt"),
                loadTerms("/text/noisy_cjk_terms.txt"),
                loadTerms("/text/noisy_term_fragments.txt"),
                loadFunctionWords("/text/function_words.txt"));
    }

    private static Set<String> loadLowercasedTerms(String resourcePath) {
        Set<String> values = new LinkedHashSet<>();
        for (String term : loadTerms(resourcePath)) {
            values.add(TextNormalization.normalizeLower(term));
        }
        return Set.copyOf(values);
    }

    private static Set<String> loadTerms(String resourcePath) {
        try (InputStream inputStream = TextQualityRules.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing text quality rules resource: " + resourcePath);
            }
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        terms.add(trimmed);
                    }
                }
            }
            return Set.copyOf(terms);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load text quality rules from " + resourcePath, exception);
        }
    }

    private static Set<Integer> loadFunctionWords(String resourcePath) {
        LinkedHashSet<Integer> codePoints = new LinkedHashSet<>();
        for (String term : loadTerms(resourcePath)) {
            term.codePoints().forEach(codePoints::add);
        }
        return Set.copyOf(codePoints);
    }
}