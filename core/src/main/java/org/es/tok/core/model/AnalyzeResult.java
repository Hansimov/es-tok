package org.es.tok.core.model;

import java.util.List;

public class AnalyzeResult {
    private final List<AnalyzeToken> tokens;
    private final AnalysisVersion version;

    public AnalyzeResult(List<AnalyzeToken> tokens, AnalysisVersion version) {
        this.tokens = tokens;
        this.version = version;
    }

    public List<AnalyzeToken> getTokens() {
        return tokens;
    }

    public AnalysisVersion getVersion() {
        return version;
    }
}