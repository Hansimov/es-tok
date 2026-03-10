package org.es.tok.core.model;

public class AnalysisVersion {
    private final String analysisHash;
    private final String vocabHash;
    private final String rulesHash;

    public AnalysisVersion(String analysisHash, String vocabHash, String rulesHash) {
        this.analysisHash = analysisHash;
        this.vocabHash = vocabHash;
        this.rulesHash = rulesHash;
    }

    public String getAnalysisHash() {
        return analysisHash;
    }

    public String getVocabHash() {
        return vocabHash;
    }

    public String getRulesHash() {
        return rulesHash;
    }
}