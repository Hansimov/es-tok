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
import org.es.tok.ngram.NgramConfig;
import org.es.tok.ngram.NgramLoader;
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
        boolean useNgram = request.paramAsBoolean("use_ngram", false);
        boolean useCache = request.paramAsBoolean("use_cache", true);
        boolean ignoreCase = request.paramAsBoolean("ignore_case", true);
        boolean splitWord = request.paramAsBoolean("split_word", true);

        // Parse vocab config parameters
        String vocabFile = request.param("vocab_file");
        String vocabListParam = request.param("vocab_list", "");
        int vocabSize = request.paramAsInt("vocab_size", -1);

        // Parse ngram config parameters
        boolean useBigram = request.paramAsBoolean("use_bigram", false);
        boolean useVcgram = request.paramAsBoolean("use_vcgram", false);
        boolean useVbgram = request.paramAsBoolean("use_vbgram", false);

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
                if (body.containsKey("use_ngram")) {
                    useNgram = (Boolean) body.get("use_ngram");
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

                // Parse vocab_config
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

                // Parse ngram_config
                if (body.containsKey("ngram_config")) {
                    Object ngramConfigObj = body.get("ngram_config");
                    if (ngramConfigObj instanceof Map<?, ?>) {
                        Map<?, ?> rawMap = (Map<?, ?>) ngramConfigObj;

                        if (rawMap.containsKey("use_bigram")) {
                            Object bigramObj = rawMap.get("use_bigram");
                            if (bigramObj instanceof Boolean) {
                                useBigram = (Boolean) bigramObj;
                            }
                        }

                        if (rawMap.containsKey("use_vcgram")) {
                            Object vcgramObj = rawMap.get("use_vcgram");
                            if (vcgramObj instanceof Boolean) {
                                useVcgram = (Boolean) vcgramObj;
                            }
                        }

                        if (rawMap.containsKey("use_vbgram")) {
                            Object vbgramObj = rawMap.get("use_vbgram");
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
        settingsBuilder.put("split_word", splitWord);

        // Build vocab_config
        if (vocabFile != null || !vocabListFromParam.isEmpty() || vocabSize >= 0) {
            if (vocabFile != null) {
                settingsBuilder.put("vocab_config.file", vocabFile);
            }
            if (!vocabListFromParam.isEmpty()) {
                settingsBuilder.putList("vocab_config.list", vocabListFromParam);
            }
            if (vocabSize >= 0) {
                settingsBuilder.put("vocab_config.size", vocabSize);
            }
        } else if (useVocab) {
            return channel -> sendErrorResponse(channel, RestStatus.BAD_REQUEST,
                    "Must provide `file` or `list` in `vocab_config` when use_vocab is true");
        }

        // Build ngram_config
        if (useNgram && (useBigram || useVcgram || useVbgram)) {
            settingsBuilder.put("ngram_config.use_bigram", useBigram);
            settingsBuilder.put("ngram_config.use_vcgram", useVcgram);
            settingsBuilder.put("ngram_config.use_vbgram", useVbgram);
        }

        Settings finalSettings = settingsBuilder.build();

        // Load configurations using internal loaders
        List<String> vocabs;
        NgramConfig ngramConfig;
        try {
            vocabs = VocabLoader.loadVocabs(finalSettings, null, useCache);
            ngramConfig = NgramLoader.loadNgramConfig(finalSettings);
        } catch (Exception e) {
            return channel -> sendErrorResponse(channel, RestStatus.BAD_REQUEST,
                    "Error loading configuration: " + e.getMessage());
        }

        // Store final values for lambda
        final String finalText = text;
        final boolean finalUseVocab = useVocab;
        final boolean finalUseCateg = useCateg;
        final List<String> finalVocabs = vocabs;
        final boolean finalIgnoreCase = ignoreCase;
        final boolean finalSplitWord = splitWord;
        final NgramConfig finalNgramConfig = ngramConfig;

        return channel -> {
            try {
                List<AnalyzeToken> tokens = analyzeText(finalText, finalUseVocab, finalUseCateg,
                        finalVocabs, finalIgnoreCase, finalSplitWord, finalNgramConfig);

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

    private List<AnalyzeToken> analyzeText(String text, boolean useVocab, boolean useCateg,
                                           List<String> vocabs, boolean ignoreCase, boolean splitWord,
                                           NgramConfig ngramConfig) throws IOException {
        List<AnalyzeToken> tokens = new ArrayList<>();

        try (EsTokAnalyzer analyzer = new EsTokAnalyzer(useVocab, useCateg, vocabs, ignoreCase, splitWord, ngramConfig)) {
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