package org.es.tok.rest;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.es.tok.core.compat.AnalysisPayloadService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

public class RestAnalyzeAction extends BaseRestHandler {
    private final AnalysisPayloadService analysisService;

    public RestAnalyzeAction() {
        this(new AnalysisPayloadService());
    }

    RestAnalyzeAction(AnalysisPayloadService analysisService) {
        this.analysisService = analysisService;
    }

    @Override
    public List<Route> routes() {
        return List.of(
                new Route(GET, "/_es_tok/analyze"),
                new Route(POST, "/_es_tok/analyze"));
    }

    @Override
    public String getName() {
        return "es_tok_analyze_action";
    }

    public boolean supportsContentStream() {
        return false;
    }

    public boolean allowsUnsafeBuffers() {
        return true;
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        Map<String, Object> payload = extractPayload(request);

        return channel -> {
            try {
                Map<String, Object> response = analysisService.analyze(payload);
                XContentBuilder builder = channel.newBuilder();
                builder.map(response);

                channel.sendResponse(new RestResponse(RestStatus.OK, builder));

            } catch (IllegalArgumentException e) {
                sendErrorResponse(channel, RestStatus.BAD_REQUEST, e.getMessage());
            } catch (Exception e) {
                sendErrorResponse(channel, RestStatus.INTERNAL_SERVER_ERROR,
                        "Analysis failed: " + e.getMessage());
            }
        };
    }

    static Map<String, Object> extractPayload(RestRequest request) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        putStringParam(payload, "text", request.param("text"));
        putBooleanParam(payload, "use_extra", request.param("use_extra"));
        putBooleanParam(payload, "use_vocab", request.param("use_vocab"));
        putBooleanParam(payload, "use_categ", request.param("use_categ"));
        putBooleanParam(payload, "use_ngram", request.param("use_ngram"));
        putBooleanParam(payload, "use_rules", request.param("use_rules"));

        if (request.hasContent()) {
            try (XContentParser parser = request.contentParser()) {
                payload.putAll(parser.map());
            }
        }
        return payload;
    }

    private static void putStringParam(Map<String, Object> payload, String key, String value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private static void putBooleanParam(Map<String, Object> payload, String key, String value) {
        if (value != null) {
            payload.put(key, Boolean.parseBoolean(value));
        }
    }

    private void sendErrorResponse(org.elasticsearch.rest.RestChannel channel, RestStatus status, String message) {
        try {
            XContentBuilder builder = channel.newBuilder();
            builder.startObject();
            builder.field("error", message);
            builder.endObject();
            channel.sendResponse(new RestResponse(status, builder));
        } catch (IOException e) {
            // Fallback error response
        }
    }
}