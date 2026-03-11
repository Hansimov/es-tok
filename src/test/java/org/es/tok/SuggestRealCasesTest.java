package org.es.tok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SuggestRealCasesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CASE_FILE = Path.of("testing/golden/suggest_real_cases.json");
    private static final Set<String> NEXT_TOKEN_PHRASE_CONTINUATION_IDS = Set.of(
            "campus_hide_seek",
            "neighbor_likes_dad",
            "teacher_raise_head",
            "qingyu_account_case",
            "xiongda_no1",
            "school_apocalypse",
            "curfew_after_dlc",
            "gold_phone_case",
            "bear_core_analysis",
            "gansu_boyfriend",
            "aerospace_base",
            "imitate_cat_actions",
            "anhe_bridge",
            "northeast_grace",
            "airport_closed",
            "defend_radish",
            "simba_daniel",
            "biscuit_union",
            "little_penguin",
            "firecracker_bag",
            "geely_ad",
            "xiongda_kuaipao",
            "silent_hill_mother");

    private RestClient client;

    @Before
    public void setup() throws Exception {
        Assume.assumeTrue("SuggestRealCasesTest requires a reachable Elasticsearch test node", TestUtils.isElasticsearchAvailable());

        String host = System.getenv().getOrDefault("ES_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("ES_PORT", "19200"));
        String username = System.getenv().getOrDefault("ES_USER", "elastic");
        String password = System.getenv().getOrDefault("ES_PASSWORD", "");

        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));

        client = RestClient.builder(new HttpHost(host, port, "https"))
                .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
                        .setDefaultCredentialsProvider(credentialsProvider)
                        .setSSLContext(TestUtils.createInsecureSSLContext()))
                .build();
    }

    @After
    public void teardown() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    @Test
    public void testRegressionMarkedRealCases() throws Exception {
        List<RealCase> cases = loadRegressionCases();
        assertFalse("Expected at least one regression-marked real case", cases.isEmpty());

        List<String> failures = new ArrayList<>();
        for (RealCase realCase : cases) {
            List<String> optionTexts = performSuggest(realCase);
            boolean matched = optionTexts.stream()
                    .limit(realCase.topK())
                    .anyMatch(realCase.expected()::contains);
            if (!matched) {
                failures.add(realCase.id() + " -> expected one of " + realCase.expected() + ", got " + optionTexts);
            }
        }

        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    private List<String> performSuggest(RealCase realCase) throws Exception {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("text", realCase.text());
        requestBody.put("mode", realCase.mode());
        requestBody.put("fields", realCase.fields());
        requestBody.putAll(defaultRequest(realCase.mode()));
        requestBody.putAll(realCase.requestOverrides());

        Request request = new Request("POST", "/bili_videos_dev6/_es_tok/suggest");
        request.setJsonEntity(MAPPER.writeValueAsString(requestBody));

        Response response = client.performRequest(request);
        try (InputStream stream = response.getEntity().getContent()) {
            JsonNode node = MAPPER.readTree(stream);
            List<String> optionTexts = new ArrayList<>();
            for (JsonNode optionNode : node.path("options")) {
                optionTexts.add(normalize(optionNode.path("text").asText()));
            }
            return optionTexts;
        }
    }

    private static Map<String, Object> defaultRequest(String mode) {
        Map<String, Object> request = new LinkedHashMap<>();
        if ("prefix".equals(mode)) {
            request.put("size", 5);
            request.put("scan_limit", 128);
        } else if ("next_token".equals(mode)) {
            request.put("size", 10);
            request.put("scan_limit", 128);
        } else if ("correction".equals(mode)) {
            request.put("size", 5);
            request.put("correction_min_length", 2);
            request.put("correction_max_edits", 2);
            request.put("correction_prefix_length", 1);
        } else if ("auto".equals(mode)) {
            request.put("size", 8);
            request.put("scan_limit", 128);
            request.put("correction_min_length", 2);
            request.put("correction_max_edits", 2);
            request.put("correction_prefix_length", 1);
        }
        return request;
    }

    private static List<RealCase> loadRegressionCases() throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(CASE_FILE, StandardCharsets.UTF_8));
        List<RealCase> cases = new ArrayList<>();
        for (JsonNode sourceNode : root) {
            for (String mode : List.of("prefix", "correction", "next_token", "auto")) {
                JsonNode caseNode = sourceNode.path(mode);
                if (caseNode.isMissingNode() || caseNode.isNull() || !caseNode.path("regression").asBoolean(false)) {
                    continue;
                }

                Set<String> expected = new LinkedHashSet<>();
                for (JsonNode item : caseNode.path("expected").path("any_of")) {
                    expected.add(normalize(item.asText()));
                }

                Map<String, Object> overrides = new LinkedHashMap<>();
                JsonNode requestNode = caseNode.path("request");
                if (requestNode.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = requestNode.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        overrides.put(field.getKey(), MAPPER.convertValue(field.getValue(), Object.class));
                    }
                }

                List<String> fieldList = new ArrayList<>();
                JsonNode fieldsNode = caseNode.path("fields");
                if (fieldsNode.isArray()) {
                    for (JsonNode item : fieldsNode) {
                        fieldList.add(item.asText());
                    }
                }
                if (fieldList.isEmpty()) {
                    fieldList = defaultFields(sourceNode.path("id").asText(), mode, caseNode);
                }

                cases.add(new RealCase(
                        sourceNode.path("id").asText() + "_" + mode,
                        mode,
                        caseNode.path("text").asText(),
                        fieldList,
                        expected,
                        caseNode.path("expected").path("top_k").asInt(defaultTopK(mode)),
                        overrides));
            }
        }
        return cases;
    }

    private static int defaultTopK(String mode) {
        if ("next_token".equals(mode)) {
            return 10;
        }
        if ("auto".equals(mode)) {
            return 8;
        }
        return 5;
    }

    private static String normalize(String text) {
        String collapsed = text == null ? "" : String.join(" ", text.trim().split("\\s+"));
        if (collapsed.isBlank() || collapsed.indexOf(' ') < 0) {
            return collapsed;
        }

        int asciiLettersOrDigits = 0;
        int nonAscii = 0;
        for (int index = 0; index < collapsed.length(); ) {
            int codePoint = collapsed.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            if (codePoint < 128 && Character.isLetterOrDigit(codePoint)) {
                asciiLettersOrDigits++;
            } else if (codePoint >= 128) {
                nonAscii++;
            }
        }

        if (nonAscii > 0 && nonAscii >= asciiLettersOrDigits) {
            return collapsed.replace(" ", "");
        }
        return collapsed;
    }

    private static List<String> defaultFields(String sourceId, String mode, JsonNode caseNode) {
        if (!"next_token".equals(mode) && !"auto".equals(mode)) {
            return List.of("title.words", "tags.words");
        }

        String kind = caseNode.path("kind").asText();
        if (kind.isBlank() && NEXT_TOKEN_PHRASE_CONTINUATION_IDS.contains(sourceId)) {
            kind = "phrase_continuation";
        }
        if ("phrase_continuation".equals(kind)) {
            return List.of("title.words");
        }
        return List.of("title.words", "tags.words");
    }

    private record RealCase(
            String id,
            String mode,
            String text,
            List<String> fields,
            Set<String> expected,
            int topK,
            Map<String, Object> requestOverrides) {
    }
}