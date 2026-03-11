package org.es.tok.rest;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestBuilderListener;
import org.es.tok.action.EsTokSuggestAction;
import org.es.tok.action.EsTokSuggestRequest;
import org.es.tok.action.EsTokSuggestResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

public class RestSuggestAction extends BaseRestHandler {

    @Override
    public List<Route> routes() {
        return List.of(
                new Route(GET, "/_es_tok/suggest"),
                new Route(POST, "/_es_tok/suggest"),
                new Route(GET, "/{index}/_es_tok/suggest"),
                new Route(POST, "/{index}/_es_tok/suggest"));
    }

    @Override
    public String getName() {
        return "es_tok_suggest_action";
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        Map<String, Object> payload = extractPayload(request);
        EsTokSuggestRequest suggestRequest = buildRequest(payload, resolveIndices(request));
        return channel -> client.execute(EsTokSuggestAction.INSTANCE, suggestRequest, new RestBuilderListener<>(channel) {
            @Override
            public org.elasticsearch.rest.RestResponse buildResponse(EsTokSuggestResponse response, org.elasticsearch.xcontent.XContentBuilder builder)
                    throws Exception {
                response.toXContent(builder, request);
                return new RestResponse(response.getStatus(), builder);
            }
        });
    }

    static String[] resolveIndices(RestRequest request) {
        String index = request.param("index");
        return index == null || index.isBlank() ? org.elasticsearch.common.Strings.EMPTY_ARRAY : new String[] { index };
    }

    static Map<String, Object> extractPayload(RestRequest request) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        putString(payload, "text", request.param("text"));
        putString(payload, "mode", request.param("mode"));
        putString(payload, "fields", request.param("fields"));
        putInt(payload, "size", request.param("size"));
        putInt(payload, "scan_limit", request.param("scan_limit"));
        putInt(payload, "min_prefix_length", request.param("min_prefix_length"));
        putInt(payload, "min_candidate_length", request.param("min_candidate_length"));
        putInt(payload, "max_fields", request.param("max_fields"));
        putBoolean(payload, "allow_compact_bigrams", request.param("allow_compact_bigrams"));
        putBoolean(payload, "cache", request.param("cache"));
        putBoolean(payload, "use_pinyin", request.param("use_pinyin"));
        putInt(payload, "correction_rare_doc_freq", request.param("correction_rare_doc_freq"));
        putInt(payload, "correction_min_length", request.param("correction_min_length"));
        putInt(payload, "correction_max_edits", request.param("correction_max_edits"));
        putInt(payload, "correction_prefix_length", request.param("correction_prefix_length"));

        if (request.hasContent()) {
            payload.putAll(request.contentParser().map());
        }
        return payload;
    }

    static EsTokSuggestRequest buildRequest(Map<String, Object> payload, String[] indices) {
        EsTokSuggestRequest request = new EsTokSuggestRequest(indices);
        request.text(asString(payload.get("text")));
        if (payload.containsKey("mode")) {
            request.mode(asString(payload.get("mode")));
        }
        request.fields(parseFields(payload.get("fields")));
        if (payload.containsKey("size")) {
            request.size(asInt(payload.get("size"), 5));
        }
        if (payload.containsKey("scan_limit")) {
            request.scanLimit(asInt(payload.get("scan_limit"), 64));
        }
        if (payload.containsKey("min_prefix_length")) {
            request.minPrefixLength(asInt(payload.get("min_prefix_length"), 1));
        }
        if (payload.containsKey("min_candidate_length")) {
            request.minCandidateLength(asInt(payload.get("min_candidate_length"), 1));
        }
        if (payload.containsKey("allow_compact_bigrams")) {
            request.allowCompactBigrams(asBoolean(payload.get("allow_compact_bigrams"), true));
        }
        if (payload.containsKey("cache")) {
            request.useCache(asBoolean(payload.get("cache"), true));
        }
        if (payload.containsKey("use_pinyin")) {
            request.usePinyin(asBoolean(payload.get("use_pinyin"), false));
        }
        if (payload.containsKey("max_fields")) {
            request.maxFields(asInt(payload.get("max_fields"), 8));
        }
        if (payload.containsKey("correction_rare_doc_freq")) {
            request.correctionRareDocFreq(asInt(payload.get("correction_rare_doc_freq"), 0));
        }
        if (payload.containsKey("correction_min_length")) {
            request.correctionMinLength(asInt(payload.get("correction_min_length"), 4));
        }
        if (payload.containsKey("correction_max_edits")) {
            request.correctionMaxEdits(asInt(payload.get("correction_max_edits"), 2));
        }
        if (payload.containsKey("correction_prefix_length")) {
            request.correctionPrefixLength(asInt(payload.get("correction_prefix_length"), 1));
        }
        return request;
    }

    private static List<String> parseFields(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String stringValue) {
            if (stringValue.isBlank()) {
                return List.of();
            }
            String[] parts = stringValue.split(",");
            List<String> fields = new ArrayList<>(parts.length);
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    fields.add(trimmed);
                }
            }
            return fields;
        }
        if (value instanceof List<?> listValue) {
            List<String> fields = new ArrayList<>(listValue.size());
            for (Object item : listValue) {
                String field = asString(item);
                if (field != null && !field.isBlank()) {
                    fields.add(field);
                }
            }
            return fields;
        }
        return List.of();
    }

    private static void putString(Map<String, Object> payload, String key, String value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private static void putInt(Map<String, Object> payload, String key, String value) {
        if (value != null) {
            payload.put(key, Integer.parseInt(value));
        }
    }

    private static void putBoolean(Map<String, Object> payload, String key, String value) {
        if (value != null) {
            payload.put(key, Boolean.parseBoolean(value));
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private static boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }
}