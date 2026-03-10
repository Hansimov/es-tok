package org.es.tok.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.es.tok.core.compat.AnalysisPayloadService;
import org.junit.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class RestAnalyzeGoldenTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void testGoldenCorpusMatchesRestAnalyzePayloadService() throws Exception {
        AnalysisPayloadService service = new AnalysisPayloadService();
        for (GoldenCase goldenCase : loadCases()) {
            assertEquals(goldenCase.name, goldenCase.expected, service.analyze(goldenCase.request));
        }
    }

    private static List<GoldenCase> loadCases() throws Exception {
        try (InputStream stream = RestAnalyzeGoldenTest.class.getClassLoader()
                .getResourceAsStream("golden/analysis_cases.json")) {
            assertNotNull(stream);
            return MAPPER.readValue(stream, new TypeReference<List<GoldenCase>>() {
            });
        }
    }

    public static class GoldenCase {
        public String name;
        public Map<String, Object> request = new LinkedHashMap<>();
        public Map<String, Object> expected = new LinkedHashMap<>();
    }
}