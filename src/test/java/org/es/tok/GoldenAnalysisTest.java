package org.es.tok;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.common.settings.Settings;
import org.es.tok.config.EsTokConfig;
import org.es.tok.config.EsTokConfigLoader;
import org.es.tok.core.compat.SettingsFlattener;
import org.es.tok.core.facade.EsTokEngine;
import org.es.tok.core.model.AnalyzeResult;
import org.es.tok.core.model.AnalyzeToken;
import org.junit.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class GoldenAnalysisTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void testGoldenCorpusMatchesCoreEngine() throws Exception {
        for (GoldenCase goldenCase : loadCases()) {
            Settings settings = SettingsFlattener.flatten(goldenCase.request);
            EsTokConfig config = EsTokConfigLoader.loadConfig(settings, null, true);
            AnalyzeResult result = new EsTokEngine(config).analyze(String.valueOf(goldenCase.request.get("text")));
            Map<String, Object> actual = toResponse(result);
            assertEquals(goldenCase.name, goldenCase.expected, actual);
        }
    }

    private static List<GoldenCase> loadCases() throws Exception {
        try (InputStream stream = GoldenAnalysisTest.class.getClassLoader()
                .getResourceAsStream("golden/analysis_cases.json")) {
            assertNotNull(stream);
            return MAPPER.readValue(stream, new TypeReference<List<GoldenCase>>() {
            });
        }
    }

    private static Map<String, Object> toResponse(AnalyzeResult result) {
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
        response.put("version", Map.of(
                "analysis_hash", result.getVersion().getAnalysisHash(),
                "vocab_hash", result.getVersion().getVocabHash(),
                "rules_hash", result.getVersion().getRulesHash()));
        return response;
    }

    public static class GoldenCase {
        public String name;
        public Map<String, Object> request;
        public Map<String, Object> expected;
    }
}