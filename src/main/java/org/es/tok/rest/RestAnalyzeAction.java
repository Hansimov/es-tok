package org.es.tok.rest;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.common.settings.Settings;
import org.es.tok.analysis.EsTokAnalyzer;
import org.es.tok.file.VocabLoader;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

public class RestAnalyzeAction extends BaseRestHandler {

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
        boolean useVocab = request.paramAsBoolean("use_vocab", true);
        boolean useCateg = request.paramAsBoolean("use_categ", true);
        boolean useCache = request.paramAsBoolean("use_cache", true);
        boolean ignoreCase = request.paramAsBoolean("ignore_case", true);
        boolean splitWord = request.paramAsBoolean("split_word", true);

        // Parse vocab config parameters
        String vocabFile = request.param("vocab_file");
        String vocabListParam = request.param("vocab_list", "");
        int vocabSize = request.paramAsInt("vocab_size", -1);

        List<String> vocabListFromParam = new ArrayList<>();
        if (vocabListParam != null && !vocabListParam.trim().isEmpty()) {
            vocabListFromParam = Arrays.asList(vocabListParam.split(","));
        }

        // Parse JSON body
        if (request.hasContent()) {
            try (XContentParser parser = request.contentParser()) {
                Map<String, Object> body = parser.map();

                if (body.containsKey("text")) {
                    text = (String) body.get("text");
                }
                if (body.containsKey("use_vocab")) {
                    useVocab = (Boolean) body.get("use_vocab");
                }
                if (body.containsKey("use_categ")) {
                    useCateg = (Boolean) body.get("use_categ");
                }
                if (body.containsKey("use_cache")) {
                    useCache = (Boolean) body.get("use_cache");
                }
                if (body.containsKey("ignore_case")) {
                    ignoreCase = (Boolean) body.get("ignore_case");
                }
                if (body.containsKey("split_word")) {
                    splitWord = (Boolean) body.get("split_word");
                }

                // New vocab_config parameter support
                if (body.containsKey("vocab_config")) {
                    Object vocabConfigObj = body.get("vocab_config");
                    if (vocabConfigObj instanceof Map<?, ?>) {
                        Map<?, ?> rawMap = (Map<?, ?>) vocabConfigObj;

                        if (rawMap.containsKey("file")) {
                            Object fileObj = rawMap.get("file");
                            if (fileObj instanceof String) {
                                vocabFile = (String) fileObj;
                            }
                        }

                        if (rawMap.containsKey("list")) {
                            Object listObj = rawMap.get("list");
                            if (listObj instanceof List<?>) {
                                List<?> rawList = (List<?>) listObj;
                                vocabListFromParam = new ArrayList<>();
                                for (Object item : rawList) {
                                    if (item instanceof String) {
                                        vocabListFromParam.add((String) item);
                                    }
                                }
                            }
                        }

                        if (rawMap.containsKey("size")) {
                            Object sizeObj = rawMap.get("size");
                            if (sizeObj instanceof Integer) {
                                vocabSize = (Integer) sizeObj;
                            }
                        }
                    }
                }
            }
        }

        // Validate required parameters
        if (text == null || text.trim().isEmpty()) {
            return channel -> sendErrorResponse(channel, RestStatus.BAD_REQUEST, "Missing required parameter: text");
        }

        // Build final vocab list using new structure if provided, otherwise use legacy
        List<String> vocabs;
        if (vocabFile != null || !vocabListFromParam.isEmpty() || vocabSize >= 0) {
            // Use new vocab_config structure
            Settings.Builder settingsBuilder = Settings.builder();
            settingsBuilder.put("use_vocab", useVocab);

            // Build vocab_config as nested settings
            if (vocabFile != null) {
                settingsBuilder.put("vocab_config.file", vocabFile);
            }
            if (!vocabListFromParam.isEmpty()) {
                settingsBuilder.putList("vocab_config.list", vocabListFromParam);
            }
            if (vocabSize >= 0) {
                settingsBuilder.put("vocab_config.size", vocabSize);
            }

            try {
                // Use caching for REST API requests
                vocabs = VocabLoader.loadVocabs(settingsBuilder.build(), null, useCache);
            } catch (Exception e) {
                return channel -> sendErrorResponse(channel, RestStatus.BAD_REQUEST,
                        "Error loading `vocab_config`: " + e.getMessage());
            }
        } else {
            return channel -> sendErrorResponse(channel, RestStatus.BAD_REQUEST,
                    "Must provide `file` or `list` in `vocab_config`");
        }

        // Store final values for lambda
        final String finalText = text;
        final boolean finalUseVocab = useVocab;
        final boolean finalUseCateg = useCateg;
        final List<String> finalVocabs = vocabs;
        final boolean finalIgnoreCase = ignoreCase;
        final boolean finalSplitWord = splitWord;

        return channel -> {
            try {
                List<AnalyzeToken> tokens = analyzeText(finalText, finalUseVocab, finalUseCateg, finalVocabs,
                        finalIgnoreCase, finalSplitWord);

                XContentBuilder builder = channel.newBuilder();
                builder.startObject();
                builder.startArray("tokens");
                for (AnalyzeToken token : tokens) {
                    builder.startObject();
                    builder.field("token", token.term);
                    builder.field("start_offset", token.startOffset);
                    builder.field("end_offset", token.endOffset);
                    builder.field("type", token.type);
                    builder.field("position", token.position);
                    builder.endObject();
                }
                builder.endArray();
                builder.endObject();

                channel.sendResponse(new RestResponse(RestStatus.OK, builder));

            } catch (Exception e) {
                sendErrorResponse(channel, RestStatus.INTERNAL_SERVER_ERROR,
                        "Analysis failed: " + e.getMessage());
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

    private List<AnalyzeToken> analyzeText(String text, boolean useVocab, boolean useCateg, List<String> vocabs,
            boolean ignoreCase, boolean splitWord) throws IOException {
        List<AnalyzeToken> tokens = new ArrayList<>();

        try (EsTokAnalyzer analyzer = new EsTokAnalyzer(useVocab, useCateg, vocabs, ignoreCase, splitWord)) {
            TokenStream tokenStream = analyzer.tokenStream("field", text);

            CharTermAttribute termAtt = tokenStream.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAtt = tokenStream.addAttribute(OffsetAttribute.class);
            PositionIncrementAttribute posIncrAtt = tokenStream.addAttribute(PositionIncrementAttribute.class);
            TypeAttribute typeAtt = tokenStream.addAttribute(TypeAttribute.class);

            tokenStream.reset();

            int position = -1;
            while (tokenStream.incrementToken()) {
                position += posIncrAtt.getPositionIncrement();
                tokens.add(new AnalyzeToken(
                        termAtt.toString(),
                        offsetAtt.startOffset(),
                        offsetAtt.endOffset(),
                        typeAtt.type(),
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
        final String type;
        final int position;

        AnalyzeToken(String term, int startOffset, int endOffset, String type, int position) {
            this.term = term;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.type = type;
            this.position = position;
        }
    }
}