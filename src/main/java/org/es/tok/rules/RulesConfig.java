package org.es.tok.rules;

/**
 * Configuration for token exclusion rules.
 * <p>
 * Controls whether rules-based token filtering is enabled and holds the rules.
 */
public class RulesConfig {
    private final boolean useRules;
    private final SearchRules searchRules;

    public RulesConfig(boolean useRules, SearchRules searchRules) {
        this.useRules = useRules;
        this.searchRules = searchRules != null ? searchRules : SearchRules.EMPTY;
    }

    public boolean isUseRules() {
        return useRules;
    }

    public SearchRules getSearchRules() {
        return searchRules;
    }

    /**
     * @return true if rules are enabled and non-empty
     */
    public boolean hasActiveRules() {
        return useRules && searchRules != null && !searchRules.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("RulesConfig{useRules=%s, searchRules=%s}", useRules, searchRules);
    }
}
