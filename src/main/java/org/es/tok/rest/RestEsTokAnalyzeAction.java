package org.es.tok.rest;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.es.tok.analysis.EsTokAnalyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

public class RestEsTokAnalyzeAction extends BaseRestHandler {

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

    @Override
    public boolean allowsUnsafeBuffers() {
        return true;
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        // Parse parameters
        String text = request.param("text");
        boolean enableVocab = request.paramAsBoolean("enable_vocab", true);
        boolean enableCateg = request.paramAsBoolean("enable_categ", false);
        String vocabsParam = request.param("vocabs");
        boolean caseSensitive = request.paramAsBoolean("case_sensitive", false);

        List<String> vocabs = new ArrayList<>();
        if (vocabsParam != null) {
            vocabs = List.of(vocabsParam.split(","));
        }

        // Parse JSON body if present
        if (request.hasContent()) {
            try (XContentParser parser = request.contentParser()) {
                String currentFieldName = null;
                XContentParser.Token token;

                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        currentFieldName = parser.currentName();
                    } else if (token.isValue()) {
                        switch (currentFieldName) {
                            case "text" -> text = parser.text();
                            case "enable_vocab" -> enableVocab = parser.booleanValue();
                            case "enable_categ" -> enableCateg = parser.booleanValue();
                            case "case_sensitive" -> caseSensitive = parser.booleanValue();
                        }
                    } else if (token == XContentParser.Token.START_ARRAY && "vocabs".equals(currentFieldName)) {
                        vocabs = new ArrayList<>();
                        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                            vocabs.add(parser.text());
                        }
                    }
                }
            }
        }

        // Store final values for lambda
        final String finalText = text;
        final boolean finalEnableVocab = enableVocab;
        final boolean finalEnableCateg = enableCateg;
        final List<String> finalVocabs = vocabs;
        final boolean finalCaseSensitive = caseSensitive;

        return channel -> {
            try {
                // Validate parameters
                if (finalText == null || finalText.isEmpty()) {
                    sendErrorResponse(channel, RestStatus.BAD_REQUEST, "Missing required parameter: text");
                    return;
                }

                if (!finalEnableVocab && !finalEnableCateg) {
                    sendErrorResponse(channel, RestStatus.BAD_REQUEST,
                            "At least one of enable_vocab or enable_categ must be true");
                    return;
                }

                if (finalEnableVocab && finalVocabs.isEmpty()) {
                    sendErrorResponse(channel, RestStatus.BAD_REQUEST,
                            "vocabs parameter required when enable_vocab is true");
                    return;
                }

                // Perform analysis
                List<AnalyzeToken> tokens = analyzeText(finalText, finalEnableVocab, finalEnableCateg, finalVocabs,
                        finalCaseSensitive);

                // Build response
                XContentBuilder builder = channel.newBuilder();
                builder.startObject();
                builder.startArray("tokens");

                for (AnalyzeToken token : tokens) {
                    builder.startObject();
                    builder.field("token", token.term);
                    builder.field("start_offset", token.startOffset);
                    builder.field("end_offset", token.endOffset);
                    builder.field("position", token.position);
                    builder.endObject();
                }

                builder.endArray();
                builder.endObject();

                channel.sendResponse(new RestResponse(RestStatus.OK, builder));

            } catch (Exception e) {
                sendErrorResponse(channel, RestStatus.INTERNAL_SERVER_ERROR,
                        "Error analyzing text: " + e.getMessage());
            }
        };
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

    private List<AnalyzeToken> analyzeText(String text, boolean enableVocab, boolean enableCateg,
            List<String> vocabs, boolean caseSensitive) throws IOException {
        List<AnalyzeToken> tokens = new ArrayList<>();

        try (EsTokAnalyzer analyzer = new EsTokAnalyzer(enableVocab, enableCateg, vocabs, caseSensitive)) {
            TokenStream tokenStream = analyzer.tokenStream("field", text);

            CharTermAttribute termAtt = tokenStream.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAtt = tokenStream.addAttribute(OffsetAttribute.class);
            PositionIncrementAttribute posIncrAtt = tokenStream.addAttribute(PositionIncrementAttribute.class);

            tokenStream.reset();

            int position = -1;
            while (tokenStream.incrementToken()) {
                position += posIncrAtt.getPositionIncrement();
                tokens.add(new AnalyzeToken(
                        termAtt.toString(),
                        offsetAtt.startOffset(),
                        offsetAtt.endOffset(),
                        position));
            }

            tokenStream.end();
        }

        return tokens;
    }

    private static class AnalyzeToken {
        final String term;
        final int startOffset;
        final int endOffset;
        final int position;

        AnalyzeToken(String term, int startOffset, int endOffset, int position) {
            this.term = term;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.position = position;
        }
    }
}