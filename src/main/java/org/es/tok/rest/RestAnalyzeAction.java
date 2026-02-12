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
import org.es.tok.tokenize.GroupAttribute;
import org.es.tok.strategy.VocabStrategy;
import org.es.tok.strategy.CategStrategy;
import org.es.tok.strategy.NgramStrategy;
import org.es.tok.extra.HantToHansConverter;
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

    public boolean allowsUnsafeBuffers() {
        return true;
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        // Initialize top-level parameters with defaults
        String text = request.param("text");
        boolean useExtra = request.paramAsBoolean("use_extra", true);
        boolean useVocab = request.paramAsBoolean("use_vocab", true);
        boolean useCateg = request.paramAsBoolean("use_categ", true);
        boolean useNgram = request.paramAsBoolean("use_ngram", true);

        // Initialize config variables
        // extra_config
        boolean ignoreCase = true;
        boolean ignoreHant = true;
        boolean dropDuplicates = true;
        boolean dropCategs = true;
        boolean dropVocabs = false;
        // categ_config
        boolean splitWord = true;
        // vocab_config
        String vocabFile = null;
        List<String> vocabList = new ArrayList<>();
        int vocabSize = -1;
        // ngram_config
        boolean useBigram = false;
        boolean useVcgram = false;
        boolean useVbgram = false;
        boolean dropCogram = true;
        // rules_config
        boolean useRules = false;
        String rulesFile = null;
        List<String> excludeTokens = new ArrayList<>();
        List<String> excludePrefixes = new ArrayList<>();
        List<String> excludeSuffixes = new ArrayList<>();
        List<String> excludeContains = new ArrayList<>();
        List<String> excludePatterns = new ArrayList<>();
        List<String> includeTokens = new ArrayList<>();
        List<String> includePrefixes = new ArrayList<>();
        List<String> includeSuffixes = new ArrayList<>();
        List<String> includeContains = new ArrayList<>();
        List<String> includePatterns = new ArrayList<>();
        List<String> decludePrefixes = new ArrayList<>();
        List<String> decludeSuffixes = new ArrayList<>();

        // Parse JSON body
        if (request.hasContent()) {
            try (XContentParser parser = request.contentParser()) {
                Map<String, Object> body = parser.map();

                if (body.containsKey("text")) {
                    text = (String) body.get("text");
                }
                if (body.containsKey("use_extra")) {
                    useExtra = (Boolean) body.get("use_extra");
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
                if (body.containsKey("use_rules")) {
                    useRules = (Boolean) body.get("use_rules");
                }

                // Parse extra_config
                if (body.containsKey("extra_config")) {
                    Object extraConfigObj = body.get("extra_config");
                    if (extraConfigObj instanceof Map<?, ?>) {
                        Map<?, ?> extraConfigMap = (Map<?, ?>) extraConfigObj;

                        if (extraConfigMap.containsKey("ignore_case")) {
                            Object ignoreCaseObj = extraConfigMap.get("ignore_case");
                            if (ignoreCaseObj instanceof Boolean) {
                                ignoreCase = (Boolean) ignoreCaseObj;
                            }
                        }
                        if (extraConfigMap.containsKey("ignore_hant")) {
                            Object ignoreHantObj = extraConfigMap.get("ignore_hant");
                            if (ignoreHantObj instanceof Boolean) {
                                ignoreHant = (Boolean) ignoreHantObj;
                            }
                        }
                        if (extraConfigMap.containsKey("drop_duplicates")) {
                            Object dropDuplicatesObj = extraConfigMap.get("drop_duplicates");
                            if (dropDuplicatesObj instanceof Boolean) {
                                dropDuplicates = (Boolean) dropDuplicatesObj;
                            }
                        }
                        if (extraConfigMap.containsKey("drop_categs")) {
                            Object dropCategsObj = extraConfigMap.get("drop_categs");
                            if (dropCategsObj instanceof Boolean) {
                                dropCategs = (Boolean) dropCategsObj;
                            }
                        }
                        if (extraConfigMap.containsKey("drop_vocabs")) {
                            Object dropVocabsObj = extraConfigMap.get("drop_vocabs");
                            if (dropVocabsObj instanceof Boolean) {
                                dropVocabs = (Boolean) dropVocabsObj;
                            }
                        }
                    }
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

                        if (ngramConfigMap.containsKey("drop_cogram")) {
                            Object dropCogramObj = ngramConfigMap.get("drop_cogram");
                            if (dropCogramObj instanceof Boolean) {
                                dropCogram = (Boolean) dropCogramObj;
                            }
                        }
                    }
                }

                // Parse rules_config
                if (body.containsKey("rules_config")) {
                    Object rulesConfigObj = body.get("rules_config");
                    if (rulesConfigObj instanceof Map<?, ?>) {
                        Map<?, ?> rulesConfigMap = (Map<?, ?>) rulesConfigObj;

                        if (rulesConfigMap.containsKey("file")) {
                            Object fileObj = rulesConfigMap.get("file");
                            if (fileObj instanceof String) {
                                rulesFile = (String) fileObj;
                            }
                        }
                        if (rulesConfigMap.containsKey("exclude_tokens")) {
                            Object listObj = rulesConfigMap.get("exclude_tokens");
                            if (listObj instanceof List<?>) {
                                for (Object item : (List<?>) listObj) {
                                    if (item instanceof String)
                                        excludeTokens.add((String) item);
                                }
                            }
                        }
                        if (rulesConfigMap.containsKey("exclude_prefixes")) {
                            Object listObj = rulesConfigMap.get("exclude_prefixes");
                            if (listObj instanceof List<?>) {
                                for (Object item : (List<?>) listObj) {
                                    if (item instanceof String)
                                        excludePrefixes.add((String) item);
                                }
                            }
                        }
                        if (rulesConfigMap.containsKey("exclude_suffixes")) {
                            Object listObj = rulesConfigMap.get("exclude_suffixes");
                            if (listObj instanceof List<?>) {
                                for (Object item : (List<?>) listObj) {
                                    if (item instanceof String)
                                        excludeSuffixes.add((String) item);
                                }
                            }
                        }
                        if (rulesConfigMap.containsKey("exclude_contains")) {
                            Object listObj = rulesConfigMap.get("exclude_contains");
                            if (listObj instanceof List<?>) {
                                for (Object item : (List<?>) listObj) {
                                    if (item instanceof String)
                                        excludeContains.add((String) item);
                                }
                            }
                        }
                        if (rulesConfigMap.containsKey("exclude_patterns")) {
                            Object listObj = rulesConfigMap.get("exclude_patterns");
                            if (listObj instanceof List<?>) {
                                for (Object item : (List<?>) listObj) {
                                    if (item instanceof String)
                                        excludePatterns.add((String) item);
                                }
                            }
                        }
                        if (rulesConfigMap.containsKey("include_tokens")) {
                            Object listObj = rulesConfigMap.get("include_tokens");
                            if (listObj instanceof List<?>) {
                                for (Object item : (List<?>) listObj) {
                                    if (item instanceof String)
                                        includeTokens.add((String) item);
                                }
                            }
                        }
                        if (rulesConfigMap.containsKey("include_prefixes")) {
                            Object listObj = rulesConfigMap.get("include_prefixes");
                            if (listObj instanceof List<?>) {
                                for (Object item : (List<?>) listObj) {
                                    if (item instanceof String)
                                        includePrefixes.add((String) item);
                                }
                            }
                        }
                        if (rulesConfigMap.containsKey("include_suffixes")) {
                            Object listObj = rulesConfigMap.get("include_suffixes");
                            if (listObj instanceof List<?>) {
                                for (Object item : (List<?>) listObj) {
                                    if (item instanceof String)
                                        includeSuffixes.add((String) item);
                                }
                            }
                        }
                        if (rulesConfigMap.containsKey("include_contains")) {
                            Object listObj = rulesConfigMap.get("include_contains");
                            if (listObj instanceof List<?>) {
                                for (Object item : (List<?>) listObj) {
                                    if (item instanceof String)
                                        includeContains.add((String) item);
                                }
                            }
                        }
                        if (rulesConfigMap.containsKey("include_patterns")) {
                            Object listObj = rulesConfigMap.get("include_patterns");
                            if (listObj instanceof List<?>) {
                                for (Object item : (List<?>) listObj) {
                                    if (item instanceof String)
                                        includePatterns.add((String) item);
                                }
                            }
                        }
                        if (rulesConfigMap.containsKey("declude_prefixes")) {
                            Object listObj = rulesConfigMap.get("declude_prefixes");
                            if (listObj instanceof List<?>) {
                                for (Object item : (List<?>) listObj) {
                                    if (item instanceof String)
                                        decludePrefixes.add((String) item);
                                }
                            }
                        }
                        if (rulesConfigMap.containsKey("declude_suffixes")) {
                            Object listObj = rulesConfigMap.get("declude_suffixes");
                            if (listObj instanceof List<?>) {
                                for (Object item : (List<?>) listObj) {
                                    if (item instanceof String)
                                        decludeSuffixes.add((String) item);
                                }
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
        settingsBuilder.put("use_extra", useExtra);
        settingsBuilder.put("use_vocab", useVocab);
        settingsBuilder.put("use_categ", useCateg);
        settingsBuilder.put("use_ngram", useNgram);

        // Build extra_config settings
        if (useExtra) {
            settingsBuilder.put("extra_config.ignore_case", ignoreCase);
            settingsBuilder.put("extra_config.ignore_hant", ignoreHant);
            settingsBuilder.put("extra_config.drop_duplicates", dropDuplicates);
            settingsBuilder.put("extra_config.drop_categs", dropCategs);
            settingsBuilder.put("extra_config.drop_vocabs", dropVocabs);
        }

        // Build categ_config settings
        if (useCateg) {
            settingsBuilder.put("categ_config.split_word", splitWord);
        }

        // Build vocab_config settings
        // When no vocab_config is explicitly provided, default to the built-in
        // vocabs.txt. The Aho-Corasick Trie is globally cached via
        // VocabStrategy.getOrCreate(), so it's only built once and shared across
        // all REST requests, avoiding OOM.
        if (useVocab && vocabFile == null && vocabList.isEmpty() && vocabSize < 0) {
            vocabFile = "vocabs.txt";
        }
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
        }

        // Build ngram_config settings
        if (useNgram && (useBigram || useVcgram || useVbgram || dropCogram)) {
            settingsBuilder.put("ngram_config.use_bigram", useBigram);
            settingsBuilder.put("ngram_config.use_vcgram", useVcgram);
            settingsBuilder.put("ngram_config.use_vbgram", useVbgram);
            settingsBuilder.put("ngram_config.drop_cogram", dropCogram);
        }

        // Build rules_config settings
        settingsBuilder.put("use_rules", useRules);
        if (useRules) {
            if (rulesFile != null) {
                settingsBuilder.put("rules_config.file", rulesFile);
            }
            if (!excludeTokens.isEmpty()) {
                settingsBuilder.putList("rules_config.exclude_tokens", excludeTokens);
            }
            if (!excludePrefixes.isEmpty()) {
                settingsBuilder.putList("rules_config.exclude_prefixes", excludePrefixes);
            }
            if (!excludeSuffixes.isEmpty()) {
                settingsBuilder.putList("rules_config.exclude_suffixes", excludeSuffixes);
            }
            if (!excludeContains.isEmpty()) {
                settingsBuilder.putList("rules_config.exclude_contains", excludeContains);
            }
            if (!excludePatterns.isEmpty()) {
                settingsBuilder.putList("rules_config.exclude_patterns", excludePatterns);
            }
            if (!includeTokens.isEmpty()) {
                settingsBuilder.putList("rules_config.include_tokens", includeTokens);
            }
            if (!includePrefixes.isEmpty()) {
                settingsBuilder.putList("rules_config.include_prefixes", includePrefixes);
            }
            if (!includeSuffixes.isEmpty()) {
                settingsBuilder.putList("rules_config.include_suffixes", includeSuffixes);
            }
            if (!includeContains.isEmpty()) {
                settingsBuilder.putList("rules_config.include_contains", includeContains);
            }
            if (!includePatterns.isEmpty()) {
                settingsBuilder.putList("rules_config.include_patterns", includePatterns);
            }
            if (!decludePrefixes.isEmpty()) {
                settingsBuilder.putList("rules_config.declude_prefixes", decludePrefixes);
            }
            if (!decludeSuffixes.isEmpty()) {
                settingsBuilder.putList("rules_config.declude_suffixes", decludeSuffixes);
            }
        }

        Settings finalSettings = settingsBuilder.build();

        // Load unified configuration
        EsTokConfig config;
        try {
            config = EsTokConfigLoader.loadConfig(finalSettings, null, true);
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
                    builder.field("group", token.group);
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

        // Build strategies, using cached VocabStrategy for performance
        VocabStrategy vocabStrategy = config.getVocabConfig().getOrCreateStrategy();
        CategStrategy categStrategy = config.getCategConfig().isUseCateg()
                ? new CategStrategy(config.getCategConfig().isSplitWord())
                : null;
        NgramStrategy ngramStrategy = config.getNgramConfig().hasAnyNgramEnabled()
                ? new NgramStrategy(config.getNgramConfig())
                : null;
        HantToHansConverter hantConverter = null;
        if (config.getExtraConfig().isIgnoreHant()) {
            try {
                hantConverter = HantToHansConverter.getInstance();
            } catch (Exception e) {
                // Ignore — proceed without hant conversion
            }
        }

        try (EsTokAnalyzer analyzer = new EsTokAnalyzer(config, vocabStrategy, categStrategy, ngramStrategy,
                hantConverter)) {
            TokenStream tokenStream = analyzer.tokenStream("field", text);

            CharTermAttribute termAtt = tokenStream.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAtt = tokenStream.addAttribute(OffsetAttribute.class);
            PositionIncrementAttribute posIncrAtt = tokenStream.addAttribute(PositionIncrementAttribute.class);
            TypeAttribute typeAtt = tokenStream.addAttribute(TypeAttribute.class);
            GroupAttribute groupAtt = tokenStream.addAttribute(GroupAttribute.class);

            tokenStream.reset();

            int position = -1;
            while (tokenStream.incrementToken()) {
                position += posIncrAtt.getPositionIncrement();
                tokens.add(new AnalyzeToken(
                        termAtt.toString(),
                        offsetAtt.startOffset(),
                        offsetAtt.endOffset(),
                        typeAtt.type(),
                        groupAtt.group(),
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
        final String group;
        final int position;

        AnalyzeToken(String term, int startOffset, int endOffset, String type, String group, int position) {
            this.term = term;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.type = type;
            this.group = group;
            this.position = position;
        }
    }
}