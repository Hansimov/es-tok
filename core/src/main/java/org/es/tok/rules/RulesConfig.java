package org.es.tok.rules;

/**
 * Configuration for token filtering rules used during the index/analyze phase.
 * <p>
 * Controls whether rules-based token filtering is enabled and holds the rules.
 */
public class RulesConfig {
    private final boolean useRules;
    private final AnalyzeRules analyzeRules;

    public RulesConfig(boolean useRules, AnalyzeRules analyzeRules) {
        this.useRules = useRules;
        this.analyzeRules = analyzeRules != null ? analyzeRules : AnalyzeRules.EMPTY;
    }

    public boolean isUseRules() {
        return useRules;
    }

    public AnalyzeRules getAnalyzeRules() {
        return analyzeRules;
    }

    /**
     * @return true if rules are enabled and non-empty
     */
    public boolean hasActiveRules() {
        return useRules && analyzeRules != null && !analyzeRules.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("RulesConfig{useRules=%s, analyzeRules=%s}", useRules, analyzeRules);
    }
}
