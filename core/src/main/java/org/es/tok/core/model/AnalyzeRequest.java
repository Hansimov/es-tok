package org.es.tok.core.model;

import org.es.tok.config.EsTokConfig;

public class AnalyzeRequest {
    private final String text;
    private final EsTokConfig config;

    public AnalyzeRequest(String text, EsTokConfig config) {
        this.text = text;
        this.config = config;
    }

    public String getText() {
        return text;
    }

    public EsTokConfig getConfig() {
        return config;
    }
}