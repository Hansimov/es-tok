package org.es.tok.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Defines matching conditions for search constraints.
 * <p>
 * A MatchCondition is satisfied when a document contains any token matching
 * any of the defined rules (all rules are OR'd within a condition):
 * <ul>
 * <li>{@code have_token} — exact token match</li>
 * <li>{@code with_prefixes} — token starts with any prefix</li>
 * <li>{@code with_suffixes} — token ends with any suffix</li>
 * <li>{@code with_contains} — token contains any substring</li>
 * <li>{@code with_patterns} — token matches any regex pattern</li>
 * </ul>
 */
public class MatchCondition {

    public static final MatchCondition EMPTY = new MatchCondition(
            Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList());

    private final List<String> haveToken;
    private final List<String> withPrefixes;
    private final List<String> withSuffixes;
    private final List<String> withContains;
    private final List<String> withPatterns;
    private final List<Pattern> compiledPatterns;

    public MatchCondition(List<String> haveToken, List<String> withPrefixes,
            List<String> withSuffixes, List<String> withContains,
            List<String> withPatterns) {
        this.haveToken = safe(haveToken);
        this.withPrefixes = safe(withPrefixes);
        this.withSuffixes = safe(withSuffixes);
        this.withContains = safe(withContains);
        this.withPatterns = safe(withPatterns);
        this.compiledPatterns = compilePatterns(this.withPatterns);
    }

    private static List<String> safe(List<String> list) {
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    private static List<Pattern> compilePatterns(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isEmpty()) {
                try {
                    compiled.add(Pattern.compile(pattern));
                } catch (PatternSyntaxException e) {
                    // Skip invalid patterns silently
                }
            }
        }
        return compiled;
    }

    /**
     * Check if a token matches this condition.
     *
     * @param token the token text to check
     * @return true if the token matches any rule in this condition
     */
    public boolean matches(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }

        // Exact match
        for (String t : haveToken) {
            if (token.equals(t)) {
                return true;
            }
        }

        // Prefix match
        for (String prefix : withPrefixes) {
            if (token.startsWith(prefix)) {
                return true;
            }
        }

        // Suffix match
        for (String suffix : withSuffixes) {
            if (token.endsWith(suffix)) {
                return true;
            }
        }

        // Contains match
        for (String sub : withContains) {
            if (token.contains(sub)) {
                return true;
            }
        }

        // Pattern match
        for (Pattern pattern : compiledPatterns) {
            if (pattern.matcher(token).matches()) {
                return true;
            }
        }

        return false;
    }

    /**
     * @return true if no rules are defined
     */
    public boolean isEmpty() {
        return haveToken.isEmpty()
                && withPrefixes.isEmpty()
                && withSuffixes.isEmpty()
                && withContains.isEmpty()
                && withPatterns.isEmpty();
    }

    // ===== Getters =====

    public List<String> getHaveToken() {
        return Collections.unmodifiableList(haveToken);
    }

    public List<String> getWithPrefixes() {
        return Collections.unmodifiableList(withPrefixes);
    }

    public List<String> getWithSuffixes() {
        return Collections.unmodifiableList(withSuffixes);
    }

    public List<String> getWithContains() {
        return Collections.unmodifiableList(withContains);
    }

    public List<String> getWithPatterns() {
        return Collections.unmodifiableList(withPatterns);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        MatchCondition that = (MatchCondition) o;
        return Objects.equals(haveToken, that.haveToken)
                && Objects.equals(withPrefixes, that.withPrefixes)
                && Objects.equals(withSuffixes, that.withSuffixes)
                && Objects.equals(withContains, that.withContains)
                && Objects.equals(withPatterns, that.withPatterns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(haveToken, withPrefixes, withSuffixes, withContains, withPatterns);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("MatchCondition{");
        List<String> parts = new ArrayList<>();
        if (!haveToken.isEmpty())
            parts.add("have_token=" + haveToken);
        if (!withPrefixes.isEmpty())
            parts.add("with_prefixes=" + withPrefixes);
        if (!withSuffixes.isEmpty())
            parts.add("with_suffixes=" + withSuffixes);
        if (!withContains.isEmpty())
            parts.add("with_contains=" + withContains);
        if (!withPatterns.isEmpty())
            parts.add("with_patterns=" + withPatterns);
        sb.append(String.join(", ", parts));
        sb.append("}");
        return sb.toString();
    }
}
