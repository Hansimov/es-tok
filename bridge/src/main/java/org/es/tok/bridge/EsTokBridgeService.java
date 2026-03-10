package org.es.tok.bridge;

import org.elasticsearch.common.settings.Settings;
import org.es.tok.config.EsTokConfig;
import org.es.tok.config.EsTokConfigLoader;
import org.es.tok.core.compat.SettingsFlattener;
import org.es.tok.core.facade.EsTokEngine;
import org.es.tok.core.model.AnalyzeResult;
import org.es.tok.core.model.AnalyzeToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EsTokBridgeService {

    public Map<String, Object> analyze(Map<String, Object> payload) {
        String text = extractText(payload);
        Settings settings = buildSettings(payload);
        EsTokConfig config = EsTokConfigLoader.loadConfig(settings, null, true);
        AnalyzeResult result = new EsTokEngine(config).analyze(text);
        return toResponse(result);
    }

    static String extractText(Map<String, Object> payload) {
        Object textObj = payload.get("text");
        if (!(textObj instanceof String) || ((String) textObj).trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required parameter: text");
        }
        return (String) textObj;
    }

    static Settings buildSettings(Map<String, Object> payload) {
        Settings.Builder builder = Settings.builder();
        SettingsFlattener.flattenInto(builder, null, payload);

        boolean useVocab = booleanValue(payload.get("use_vocab"), true);
        Object vocabConfig = payload.get("vocab_config");
        if (useVocab && !(vocabConfig instanceof Map<?, ?>)) {
            builder.put("vocab_config.file", "vocabs.txt");
        }

        return builder.build();
    }

    static Map<String, Object> toResponse(AnalyzeResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        List<Map<String, Object>> tokens = new ArrayList<>();
        for (AnalyzeToken token : result.getTokens()) {
            Map<String, Object> tokenMap = new LinkedHashMap<>();
            tokenMap.put("token", token.getToken());
            tokenMap.put("start_offset", token.getStartOffset());
            tokenMap.put("end_offset", token.getEndOffset());
            tokenMap.put("type", token.getType());
            tokenMap.put("group", token.getGroup());
            tokenMap.put("position", token.getPosition());
            tokens.add(tokenMap);
        }
        response.put("tokens", tokens);

        Map<String, Object> version = new LinkedHashMap<>();
        version.put("analysis_hash", result.getVersion().getAnalysisHash());
        version.put("vocab_hash", result.getVersion().getVocabHash());
        version.put("rules_hash", result.getVersion().getRulesHash());
        response.put("version", version);
        return response;
    }

    static boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return defaultValue;
    }
}