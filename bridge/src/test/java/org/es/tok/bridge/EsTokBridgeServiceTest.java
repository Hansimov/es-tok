package org.es.tok.bridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class EsTokBridgeServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void testGoldenCorpusMatchesBridgeService() throws Exception {
        EsTokBridgeService service = new EsTokBridgeService();
        for (GoldenCase goldenCase : loadGoldenCases()) {
            Map<String, Object> response = service.analyze(goldenCase.request);
            assertEquals(goldenCase.name, goldenCase.expected, response);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMissingTextThrows() {
        new EsTokBridgeService().analyze(Map.of("use_vocab", true));
    }

    @Test
    public void testApiSpecMatchesResponseShape() throws Exception {
        Map<String, Object> spec;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("bridge-api.json")) {
            assertNotNull(stream);
            spec = MAPPER.readValue(stream, new TypeReference<Map<String, Object>>() {
            });
        }

        Map<String, Object> request = castMap(spec.get("request"));
        assertEquals(List.of("text"), request.get("required"));

        Map<String, Object> response = castMap(spec.get("response"));
        Map<String, Object> responseProperties = castMap(response.get("properties"));
        assertEquals(List.of("tokens", "version"), List.copyOf(responseProperties.keySet()));

        Map<String, Object> tokens = castMap(responseProperties.get("tokens"));
        Map<String, Object> tokenItems = castMap(tokens.get("items"));
        Map<String, Object> tokenProperties = castMap(tokenItems.get("properties"));
        assertEquals(List.of("token", "start_offset", "end_offset", "type", "group", "position"),
                List.copyOf(tokenProperties.keySet()));

        Map<String, Object> version = castMap(responseProperties.get("version"));
        Map<String, Object> versionProperties = castMap(version.get("properties"));
        assertEquals(List.of("analysis_hash", "vocab_hash", "rules_hash"),
                List.copyOf(versionProperties.keySet()));

        List<Map<String, Object>> examples = castList(spec.get("examples"));
        assertFalse(examples.isEmpty());
        assertTrue(examples.get(0).containsKey("case"));
        assertTrue(examples.get(0).containsKey("title"));
    }

    private static List<GoldenCase> loadGoldenCases() throws Exception {
        try (InputStream stream = EsTokBridgeServiceTest.class.getClassLoader()
                .getResourceAsStream("golden/analysis/analysis_cases.json")) {
            assertNotNull(stream);
            return MAPPER.readValue(stream, new TypeReference<List<GoldenCase>>() {
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    public static class GoldenCase {
        public String name;
        public Map<String, Object> request = new LinkedHashMap<>();
        public Map<String, Object> expected = new LinkedHashMap<>();
    }
}