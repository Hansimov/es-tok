package org.es.tok.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertNotNull;

public final class GoldenAnalysisCaseLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESOURCE_PATH = "golden/analysis/analysis_cases.json";

    private GoldenAnalysisCaseLoader() {
    }

    public static List<GoldenCase> loadCases() throws Exception {
        try (InputStream stream = GoldenAnalysisCaseLoader.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            assertNotNull("Missing golden analysis fixture: " + RESOURCE_PATH, stream);
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