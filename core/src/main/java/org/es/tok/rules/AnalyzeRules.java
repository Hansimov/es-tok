package org.es.tok.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Defines token exclusion, inclusion, and context-dependent exclusion (declude)
 * rules for the index/analyze phase.
 * <p>
 * These rules filter tokens during analysis (indexing and REST analyze),
 * not during search. The name "AnalyzeRules" reflects this: rules are
 * applied at tokenization time to control which tokens are kept or excluded.
 * <p>
 * Evaluation order:
 * <ol>
 * <li>If token matches any {@code include_*} rule → <b>keep</b></li>
 * <li>If token matches any {@code exclude_*} rule → <b>exclude</b></li>
 * <li>If token matches any {@code declude_*} rule (context-dependent) →
 * <b>exclude</b></li>
 * <li>Otherwise → <b>keep</b></li>
 * </ol>
 * <p>
 * Declude rules ({@code declude_prefixes}, {@code declude_suffixes}) are
 * context-dependent:
 * they require the full set of token texts to check whether the base form
 * exists.
 * <ul>
 * <li>{@code declude_prefixes}: if token starts with a prefix AND token without
 * that prefix exists in all tokens → exclude</li>
 * <li>{@code declude_suffixes}: if token ends with a suffix AND token without
 * that suffix exists in all tokens → exclude</li>
 * </ul>
 */
public class AnalyzeRules {

    public static final AnalyzeRules EMPTY = new AnalyzeRules(
            Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList());

    // Exclude rules
    private final List<String> excludeTokens;
    private final Set<String> excludeTokensSet; // HashSet for O(1) lookup
    private final List<String> excludePrefixes;
    private final List<String> excludeSuffixes;
    private final List<String> excludeContains;
    private final List<String> excludePatterns;
    private final List<Pattern> compiledExcludePatterns;

    // Include rules (higher priority than exclude)
    private final List<String> includeTokens;
    private final Set<String> includeTokensSet; // HashSet for O(1) lookup
    private final List<String> includePrefixes;
    private final List<String> includeSuffixes;
    private final List<String> includeContains;
    private final List<String> includePatterns;
    private final List<Pattern> compiledIncludePatterns;

    // Declude rules (context-dependent exclusion)
    private final List<String> decludePrefixes;
    private final List<String> decludeSuffixes;

    /**
     * Constructor with exclude and include rules (declude defaults to empty).
     */
    public AnalyzeRules(List<String> excludeTokens, List<String> excludePrefixes,
            List<String> excludeSuffixes, List<String> excludeContains,
            List<String> excludePatterns,
            List<String> includeTokens, List<String> includePrefixes,
            List<String> includeSuffixes, List<String> includeContains,
            List<String> includePatterns) {
        this(excludeTokens, excludePrefixes, excludeSuffixes, excludeContains, excludePatterns,
                includeTokens, includePrefixes, includeSuffixes, includeContains, includePatterns,
                Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Full constructor with exclude, include, and declude rules.
     */
    public AnalyzeRules(List<String> excludeTokens, List<String> excludePrefixes,
            List<String> excludeSuffixes, List<String> excludeContains,
            List<String> excludePatterns,
            List<String> includeTokens, List<String> includePrefixes,
            List<String> includeSuffixes, List<String> includeContains,
            List<String> includePatterns,
            List<String> decludePrefixes, List<String> decludeSuffixes) {

        this.excludeTokens = safe(excludeTokens);
        this.excludeTokensSet = new HashSet<>(this.excludeTokens);
        this.excludePrefixes = safe(excludePrefixes);
        this.excludeSuffixes = safe(excludeSuffixes);
        this.excludeContains = safe(excludeContains);
        this.excludePatterns = safe(excludePatterns);
        this.compiledExcludePatterns = compilePatterns(this.excludePatterns);

        this.includeTokens = safe(includeTokens);
        this.includeTokensSet = new HashSet<>(this.includeTokens);
        this.includePrefixes = safe(includePrefixes);
        this.includeSuffixes = safe(includeSuffixes);
        this.includeContains = safe(includeContains);
        this.includePatterns = safe(includePatterns);
        this.compiledIncludePatterns = compilePatterns(this.includePatterns);

        this.decludePrefixes = safe(decludePrefixes);
        this.decludeSuffixes = safe(decludeSuffixes);
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
     * Check if a token should be excluded based on the rules (without context).
     * <p>
     * This method does NOT check declude rules (which require the full token set).
     * Use {@link #shouldExclude(String, Set)} for context-aware filtering.
     *
     * @param token the token text to check
     * @return true if the token should be excluded
     */
    public boolean shouldExclude(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }

        // 1. Check include rules first (higher priority) — if included, keep
        if (shouldInclude(token)) {
            return false;
        }

        // 2. Check exclude rules
        return matchesExcludeRules(token);
    }

    /**
     * Check if a token should be excluded, with context-dependent declude rules.
     * <p>
     * Include rules take priority. Then exclude rules. Then declude rules
     * (which check if the base form exists in the token set).
     *
     * @param token         the token text to check
     * @param allTokenTexts the full set of all token texts (for declude lookups)
     * @return true if the token should be excluded
     */
    public boolean shouldExclude(String token, Set<String> allTokenTexts) {
        if (token == null || token.isEmpty()) {
            return false;
        }

        // 1. Check include rules first (higher priority) — if included, keep
        if (shouldInclude(token)) {
            return false;
        }

        // 2. Check exclude rules
        if (matchesExcludeRules(token)) {
            return true;
        }

        // 3. Check declude rules (context-dependent)
        if (matchesDecludeRules(token, allTokenTexts)) {
            return true;
        }

        return false;
    }

    /**
     * Check if a token matches any exclude rule.
     */
    private boolean matchesExcludeRules(String token) {
        // Exact match (O(1) via HashSet)
        if (!excludeTokensSet.isEmpty() && excludeTokensSet.contains(token)) {
            return true;
        }

        // Prefix match
        for (String prefix : excludePrefixes) {
            if (token.startsWith(prefix)) {
                return true;
            }
        }

        // Suffix match
        for (String suffix : excludeSuffixes) {
            if (token.endsWith(suffix)) {
                return true;
            }
        }

        // Contains match
        for (String sub : excludeContains) {
            if (token.contains(sub)) {
                return true;
            }
        }

        // Regex pattern match
        for (Pattern pattern : compiledExcludePatterns) {
            if (pattern.matcher(token).matches()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a token matches any declude rule (context-dependent).
     * <p>
     * Declude rules check whether removing a prefix/suffix yields a base form
     * that exists in the token set. If so, the token is excluded.
     */
    private boolean matchesDecludeRules(String token, Set<String> allTokenTexts) {
        if (allTokenTexts == null || allTokenTexts.isEmpty()) {
            return false;
        }

        // Check declude prefixes
        for (String prefix : decludePrefixes) {
            if (token.length() > prefix.length() && token.startsWith(prefix)) {
                String baseForm = token.substring(prefix.length());
                if (allTokenTexts.contains(baseForm)) {
                    return true;
                }
            }
        }

        // Check declude suffixes
        for (String suffix : decludeSuffixes) {
            if (token.length() > suffix.length() && token.endsWith(suffix)) {
                String baseForm = token.substring(0, token.length() - suffix.length());
                if (allTokenTexts.contains(baseForm)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Check if a token matches any include rule.
     */
    private boolean shouldInclude(String token) {
        if (!hasIncludeRules()) {
            return false;
        }

        // Exact match (O(1) via HashSet)
        if (!includeTokensSet.isEmpty() && includeTokensSet.contains(token)) {
            return true;
        }

        // Prefix match
        for (String prefix : includePrefixes) {
            if (token.startsWith(prefix)) {
                return true;
            }
        }

        // Suffix match
        for (String suffix : includeSuffixes) {
            if (token.endsWith(suffix)) {
                return true;
            }
        }

        // Contains match
        for (String sub : includeContains) {
            if (token.contains(sub)) {
                return true;
            }
        }

        // Regex pattern match
        for (Pattern pattern : compiledIncludePatterns) {
            if (pattern.matcher(token).matches()) {
                return true;
            }
        }

        return false;
    }

    /**
     * @return true if any include rules are defined
     */
    private boolean hasIncludeRules() {
        return !includeTokens.isEmpty()
                || !includePrefixes.isEmpty()
                || !includeSuffixes.isEmpty()
                || !includeContains.isEmpty()
                || !includePatterns.isEmpty();
    }

    /**
     * @return true if no exclusion or declude rules are defined
     *         (include-only rules don't cause filtering)
     */
    public boolean isEmpty() {
        return excludeTokens.isEmpty()
                && excludePrefixes.isEmpty()
                && excludeSuffixes.isEmpty()
                && excludeContains.isEmpty()
                && excludePatterns.isEmpty()
                && decludePrefixes.isEmpty()
                && decludeSuffixes.isEmpty();
    }

    // ===== Exclude getters =====

    public List<String> getExcludeTokens() {
        return Collections.unmodifiableList(excludeTokens);
    }

    public List<String> getExcludePrefixes() {
        return Collections.unmodifiableList(excludePrefixes);
    }

    public List<String> getExcludeSuffixes() {
        return Collections.unmodifiableList(excludeSuffixes);
    }

    public List<String> getExcludeContains() {
        return Collections.unmodifiableList(excludeContains);
    }

    public List<String> getExcludePatterns() {
        return Collections.unmodifiableList(excludePatterns);
    }

    // ===== Include getters =====

    public List<String> getIncludeTokens() {
        return Collections.unmodifiableList(includeTokens);
    }

    public List<String> getIncludePrefixes() {
        return Collections.unmodifiableList(includePrefixes);
    }

    public List<String> getIncludeSuffixes() {
        return Collections.unmodifiableList(includeSuffixes);
    }

    public List<String> getIncludeContains() {
        return Collections.unmodifiableList(includeContains);
    }

    public List<String> getIncludePatterns() {
        return Collections.unmodifiableList(includePatterns);
    }

    // ===== Declude getters =====

    public List<String> getDecludePrefixes() {
        return Collections.unmodifiableList(decludePrefixes);
    }

    public List<String> getDecludeSuffixes() {
        return Collections.unmodifiableList(decludeSuffixes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        AnalyzeRules that = (AnalyzeRules) o;
        return Objects.equals(excludeTokens, that.excludeTokens)
                && Objects.equals(excludePrefixes, that.excludePrefixes)
                && Objects.equals(excludeSuffixes, that.excludeSuffixes)
                && Objects.equals(excludeContains, that.excludeContains)
                && Objects.equals(excludePatterns, that.excludePatterns)
                && Objects.equals(includeTokens, that.includeTokens)
                && Objects.equals(includePrefixes, that.includePrefixes)
                && Objects.equals(includeSuffixes, that.includeSuffixes)
                && Objects.equals(includeContains, that.includeContains)
                && Objects.equals(includePatterns, that.includePatterns)
                && Objects.equals(decludePrefixes, that.decludePrefixes)
                && Objects.equals(decludeSuffixes, that.decludeSuffixes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(excludeTokens, excludePrefixes, excludeSuffixes, excludeContains, excludePatterns,
                includeTokens, includePrefixes, includeSuffixes, includeContains, includePatterns,
                decludePrefixes, decludeSuffixes);
    }

    @Override
    public String toString() {
        return String.format(
                "AnalyzeRules{excludeTokens=%s, excludePrefixes=%s, excludeSuffixes=%s, excludeContains=%s, excludePatterns=%s, "
                        + "includeTokens=%s, includePrefixes=%s, includeSuffixes=%s, includeContains=%s, includePatterns=%s, "
                        + "decludePrefixes=%s, decludeSuffixes=%s}",
                excludeTokens, excludePrefixes, excludeSuffixes, excludeContains, excludePatterns,
                includeTokens, includePrefixes, includeSuffixes, includeContains, includePatterns,
                decludePrefixes, decludeSuffixes);
    }
}
