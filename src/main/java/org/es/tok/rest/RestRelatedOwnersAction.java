package org.es.tok.rest;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestBuilderListener;
import org.es.tok.action.EsTokRelatedOwnersAction;
import org.es.tok.action.EsTokRelatedOwnersRequest;
import org.es.tok.action.EsTokRelatedOwnersResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

public class RestRelatedOwnersAction extends BaseRestHandler {

    @Override
    public List<Route> routes() {
        return List.of(
                new Route(GET, "/_es_tok/related_owners_by_tokens"),
                new Route(POST, "/_es_tok/related_owners_by_tokens"),
                new Route(GET, "/{index}/_es_tok/related_owners_by_tokens"),
                new Route(POST, "/{index}/_es_tok/related_owners_by_tokens"));
    }

    @Override
    public String getName() {
        return "es_tok_related_owners_action";
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        Map<String, Object> payload = extractPayload(request);
        EsTokRelatedOwnersRequest relatedOwnersRequest = buildRequest(payload, RestSuggestAction.resolveIndices(request));
        return channel -> client.execute(EsTokRelatedOwnersAction.INSTANCE, relatedOwnersRequest, new RestBuilderListener<>(channel) {
            @Override
            public RestResponse buildResponse(EsTokRelatedOwnersResponse response, org.elasticsearch.xcontent.XContentBuilder builder)
                    throws Exception {
                response.toXContent(builder, request);
                return new RestResponse(response.getStatus(), builder);
            }
        });
    }

    static Map<String, Object> extractPayload(RestRequest request) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        putString(payload, "text", request.param("text"));
        putString(payload, "fields", request.param("fields"));
        putInt(payload, "size", request.param("size"));
        putInt(payload, "scan_limit", request.param("scan_limit"));
        putInt(payload, "max_fields", request.param("max_fields"));
        putBoolean(payload, "use_pinyin", request.param("use_pinyin"));
        if (request.hasContent()) {
            payload.putAll(request.contentParser().map());
        }
        return payload;
    }

    static EsTokRelatedOwnersRequest buildRequest(Map<String, Object> payload, String[] indices) {
        EsTokRelatedOwnersRequest request = new EsTokRelatedOwnersRequest(indices);
        request.text(asString(payload.get("text")));
        request.fields(parseFields(payload.get("fields")));
        if (payload.containsKey("size")) {
            request.size(asInt(payload.get("size"), 10));
        }
        if (payload.containsKey("scan_limit")) {
            request.scanLimit(asInt(payload.get("scan_limit"), 128));
        }
        if (payload.containsKey("max_fields")) {
            request.maxFields(asInt(payload.get("max_fields"), 8));
        }
        if (payload.containsKey("use_pinyin")) {
            request.usePinyin(asBoolean(payload.get("use_pinyin"), false));
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
