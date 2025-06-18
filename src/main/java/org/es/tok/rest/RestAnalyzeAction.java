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
import org.es.tok.config.EsTokConfig;
import org.es.tok.config.EsTokConfigLoader;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import java.io.IOException;
import java.util.ArrayList;
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
        // Parse top-level parameters
        String text = request.param("text");
        boolean useVocab = request.paramAsBoolean("use_vocab", true);
        boolean useCateg = request.paramAsBoolean("use_categ", true);
        boolean useNgram = request.paramAsBoolean("use_ngram", false);
        boolean useCache = request.paramAsBoolean("use_cache", true);
        boolean ignoreCase = request.paramAsBoolean("ignore_case", true);
        boolean dropDuplicates = request.paramAsBoolean("drop_duplicates", false);

        // Initialize config objects
        String vocabFile = null;
        List<String> vocabList = new ArrayList<>();
        int vocabSize = -1;
        boolean splitWord = true;
        boolean useBigram = false;
        boolean useVcgram = false;
        boolean useVbgram = false;

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
                if (body.containsKey("use_ngram")) {
                    useNgram = (Boolean) body.get("use_ngram");
                }
                if (body.containsKey("use_cache")) {
                    useCache = (Boolean) body.get("use_cache");
                }
                if (body.containsKey("ignore_case")) {
                    ignoreCase = (Boolean) body.get("ignore_case");
                }
                if (body.containsKey("drop_duplicates")) {
                    dropDuplicates = (Boolean) body.get("drop_duplicates");
                }

                // Parse vocab_config
                if (body.containsKey("vocab_config")) {
                    Object vocabConfigObj = body.get("vocab_config");
                    if (vocabConfigObj instanceof Map<?, ?>) {
                        Map<?, ?> vocabConfigMap = (Map<?, ?>) vocabConfigObj;

                        if (vocabConfigMap.containsKey("file")) {
                            Object fileObj = vocabConfigMap.get("file");
                            if (fileObj instanceof String) {
                                vocabFile = (String) fileObj;
                            }
                        }

                        if (vocabConfigMap.containsKey("list")) {
                            Object listObj = vocabConfigMap.get("list");
                            if (listObj instanceof List<?>) {
                                List<?> rawList = (List<?>) listObj;
                                vocabList = new ArrayList<>();
                                for (Object item : rawList) {
                                    if (item instanceof String) {
                                        vocabList.add((String) item);
                                    }
                                }
                            }
                        }

                        if (vocabConfigMap.containsKey("size")) {
                            Object sizeObj = vocabConfigMap.get("size");
                            if (sizeObj instanceof Integer) {
                                vocabSize = (Integer) sizeObj;
                            }
                        }
                    }
                }

                // Parse categ_config
                if (body.containsKey("categ_config")) {
                    Object categConfigObj = body.get("categ_config");
                    if (categConfigObj instanceof Map<?, ?>) {
                        Map<?, ?> categConfigMap = (Map<?, ?>) categConfigObj;

                        if (categConfigMap.containsKey("split_word")) {
                            Object splitWordObj = categConfigMap.get("split_word");
                            if (splitWordObj instanceof Boolean) {
                                splitWord = (Boolean) splitWordObj;
                            }
                        }
                    }
                }

                // Parse ngram_config
                if (body.containsKey("ngram_config")) {
                    Object ngramConfigObj = body.get("ngram_config");
                    if (ngramConfigObj instanceof Map<?, ?>) {
                        Map<?, ?> ngramConfigMap = (Map<?, ?>) ngramConfigObj;

                        if (ngramConfigMap.containsKey("use_bigram")) {
                            Object bigramObj = ngramConfigMap.get("use_bigram");
                            if (bigramObj instanceof Boolean) {
                                useBigram = (Boolean) bigramObj;
                            }
                        }

                        if (ngramConfigMap.containsKey("use_vcgram")) {
                            Object vcgramObj = ngramConfigMap.get("use_vcgram");
                            if (vcgramObj instanceof Boolean) {
                                useVcgram = (Boolean) vcgramObj;
                            }
                        }

                        if (ngramConfigMap.containsKey("use_vbgram")) {
                            Object vbgramObj = ngramConfigMap.get("use_vbgram");
                            if (vbgramObj instanceof Boolean) {
                                useVbgram = (Boolean) vbgramObj;
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

        // Build Settings for internal use
        Settings.Builder settingsBuilder = Settings.builder();
        settingsBuilder.put("use_vocab", useVocab);
        settingsBuilder.put("use_categ", useCateg);
        settingsBuilder.put("use_ngram", useNgram);
        settingsBuilder.put("ignore_case", ignoreCase);
        settingsBuilder.put("drop_duplicates", dropDuplicates);

        // Build vocab_config settings
        if (vocabFile != null || !vocabList.isEmpty() || vocabSize >= 0) {
            if (vocabFile != null) {
                settingsBuilder.put("vocab_config.file", vocabFile);
            }
            if (!vocabList.isEmpty()) {
                settingsBuilder.putList("vocab_config.list", vocabList);
            }
            if (vocabSize >= 0) {
                settingsBuilder.put("vocab_config.size", vocabSize);
            }
        } else if (useVocab) {
            return channel -> sendErrorResponse(channel, RestStatus.BAD_REQUEST,
                    "Must provide `file` or `list` in `vocab_config` when use_vocab is true");
        }

        // Build categ_config settings
        if (useCateg) {
            settingsBuilder.put("categ_config.split_word", splitWord);
        }

        // Build ngram_config settings
        if (useNgram && (useBigram || useVcgram || useVbgram)) {
            settingsBuilder.put("ngram_config.use_bigram", useBigram);
            settingsBuilder.put("ngram_config.use_vcgram", useVcgram);
            settingsBuilder.put("ngram_config.use_vbgram", useVbgram);
        }

        Settings finalSettings = settingsBuilder.build();

        // Load unified configuration
        EsTokConfig config;
        try {
            config = EsTokConfigLoader.loadConfig(finalSettings, null, useCache);
        } catch (Exception e) {
            return channel -> sendErrorResponse(channel, RestStatus.BAD_REQUEST,
                    "Error loading configuration: " + e.getMessage());
        }

        // Store final values for lambda
        final String finalText = text;
        final EsTokConfig finalConfig = config;

        return channel -> {
            try {
                List<AnalyzeToken> tokens = analyzeText(finalText, finalConfig);

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

    private List<AnalyzeToken> analyzeText(String text, EsTokConfig config) throws IOException {
        List<AnalyzeToken> tokens = new ArrayList<>();

        try (EsTokAnalyzer analyzer = new EsTokAnalyzer(config)) {
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