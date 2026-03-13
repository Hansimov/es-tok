package org.es.tok.core.golden;

import org.elasticsearch.common.settings.Settings;
import org.es.tok.config.EsTokConfig;
import org.es.tok.config.EsTokConfigLoader;
import org.es.tok.core.compat.SettingsFlattener;
import org.es.tok.core.facade.EsTokEngine;
import org.es.tok.core.model.AnalyzeResult;
import org.es.tok.core.model.AnalyzeToken;
import org.es.tok.support.GoldenAnalysisCaseLoader;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class GoldenAnalysisTest {
    @Test
    public void testGoldenCorpusMatchesCoreEngine() throws Exception {
        for (GoldenAnalysisCaseLoader.GoldenCase goldenCase : GoldenAnalysisCaseLoader.loadCases()) {
            Settings settings = SettingsFlattener.flatten(goldenCase.request);
            EsTokConfig config = EsTokConfigLoader.loadConfig(settings, null, true);
            AnalyzeResult result = new EsTokEngine(config).analyze(String.valueOf(goldenCase.request.get("text")));
            Map<String, Object> actual = toResponse(result);
            assertEquals(goldenCase.name, goldenCase.expected, actual);
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
}