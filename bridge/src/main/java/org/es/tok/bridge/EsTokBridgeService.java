package org.es.tok.bridge;

import org.es.tok.core.payload.AnalysisPayloadService;

import java.util.Map;

public class EsTokBridgeService {
    private final AnalysisPayloadService delegate = new AnalysisPayloadService();

    public Map<String, Object> analyze(Map<String, Object> payload) {
        return delegate.analyze(payload);
    }
}