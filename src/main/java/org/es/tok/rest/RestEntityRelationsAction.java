package org.es.tok.rest;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.action.RestBuilderListener;
import org.es.tok.action.EsTokEntityRelationRequest;
import org.es.tok.action.EsTokEntityRelationResponse;
import org.es.tok.action.EsTokEntityRelationsAction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

public class RestEntityRelationsAction extends BaseRestHandler {

    @Override
    public List<Route> routes() {
        return List.of(
                new Route(GET, "/_es_tok/related_videos_by_videos"),
                new Route(POST, "/_es_tok/related_videos_by_videos"),
                new Route(GET, "/_es_tok/related_owners_by_videos"),
                new Route(POST, "/_es_tok/related_owners_by_videos"),
                new Route(GET, "/_es_tok/related_videos_by_owners"),
                new Route(POST, "/_es_tok/related_videos_by_owners"),
                new Route(GET, "/_es_tok/related_owners_by_owners"),
                new Route(POST, "/_es_tok/related_owners_by_owners"),
                new Route(GET, "/{index}/_es_tok/related_videos_by_videos"),
                new Route(POST, "/{index}/_es_tok/related_videos_by_videos"),
                new Route(GET, "/{index}/_es_tok/related_owners_by_videos"),
                new Route(POST, "/{index}/_es_tok/related_owners_by_videos"),
                new Route(GET, "/{index}/_es_tok/related_videos_by_owners"),
                new Route(POST, "/{index}/_es_tok/related_videos_by_owners"),
                new Route(GET, "/{index}/_es_tok/related_owners_by_owners"),
                new Route(POST, "/{index}/_es_tok/related_owners_by_owners"));
    }

    @Override
    public String getName() {
        return "es_tok_entity_relations_action";
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        String relation = resolveRelation(request.path());
        Map<String, Object> payload = extractPayload(request);
        EsTokEntityRelationRequest relationRequest = buildRequest(relation, payload, RestSuggestAction.resolveIndices(request));
        return channel -> client.execute(EsTokEntityRelationsAction.INSTANCE, relationRequest, new RestBuilderListener<>(channel) {
            @Override
            public RestResponse buildResponse(EsTokEntityRelationResponse response, org.elasticsearch.xcontent.XContentBuilder builder)
                    throws Exception {
                response.toXContent(builder, request);
                return new RestResponse(response.getStatus(), builder);
            }
        });
    }

    static Map<String, Object> extractPayload(RestRequest request) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        putString(payload, "bvids", request.param("bvids"));
        putString(payload, "bvid", request.param("bvid"));
        putString(payload, "mids", request.param("mids"));
        putString(payload, "mid", request.param("mid"));
        putInt(payload, "size", request.param("size"));
        putInt(payload, "scan_limit", request.param("scan_limit"));
        if (request.hasContent()) {
            payload.putAll(request.contentParser().map());
        }
        return payload;
    }

    static EsTokEntityRelationRequest buildRequest(String relation, Map<String, Object> payload, String[] indices) {
        EsTokEntityRelationRequest request = new EsTokEntityRelationRequest(indices);
        request.relation(relation);
        request.bvids(parseStrings(payload.containsKey("bvids") ? payload.get("bvids") : payload.get("bvid")));
        request.mids(parseLongs(payload.containsKey("mids") ? payload.get("mids") : payload.get("mid")));
        if (payload.containsKey("size")) {
            request.size(asInt(payload.get("size"), 10));
        }
        if (payload.containsKey("scan_limit")) {
            request.scanLimit(asInt(payload.get("scan_limit"), 128));
        }
        return request;
    }

    static String resolveRelation(String path) {
        for (String relation : List.of(
                EsTokEntityRelationRequest.RELATED_VIDEOS_BY_VIDEOS,
                EsTokEntityRelationRequest.RELATED_OWNERS_BY_VIDEOS,
                EsTokEntityRelationRequest.RELATED_VIDEOS_BY_OWNERS,
                EsTokEntityRelationRequest.RELATED_OWNERS_BY_OWNERS)) {
            if (path.endsWith("/" + relation)) {
                return relation;
            }
        }
        throw new IllegalArgumentException("Unsupported relation path: " + path);
    }

    private static List<String> parseStrings(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String stringValue) {
            if (stringValue.isBlank()) {
                return List.of();
            }
            String[] parts = stringValue.split(",");
            List<String> items = new ArrayList<>(parts.length);
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isBlank()) {
                    items.add(trimmed);
                }
            }
            return items;
        }
        if (value instanceof List<?> listValue) {
            List<String> items = new ArrayList<>(listValue.size());
            for (Object item : listValue) {
                if (item != null) {
                    String text = item.toString().trim();
                    if (!text.isBlank()) {
                        items.add(text);
                    }
                }
            }
            return items;
        }
        return List.of(value.toString());
    }

    private static List<Long> parseLongs(Object value) {
        List<String> texts = parseStrings(value);
        List<Long> longs = new ArrayList<>(texts.size());
        for (String text : texts) {
            longs.add(Long.parseLong(text));
        }
        return longs;
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

    private static int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }
}