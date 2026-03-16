package org.es.tok.action;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.DefaultShardOperationFailedException;
import org.elasticsearch.action.support.broadcast.BroadcastShardOperationFailedException;
import org.elasticsearch.action.support.broadcast.TransportBroadcastAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ProjectState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.project.ProjectResolver;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.es.tok.suggest.CachedShardSuggestService;
import org.es.tok.suggest.LuceneIndexSuggester;
import org.es.tok.suggest.OwnerBackedSuggestService;
import org.es.tok.suggest.PinyinSupport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class TransportEsTokSuggestAction extends TransportBroadcastAction<
        EsTokSuggestRequest,
        EsTokSuggestResponse,
        ShardEsTokSuggestRequest,
        ShardEsTokSuggestResponse> {

    private static final int MAX_LONG_TEXT_FALLBACKS = 3;

    private final IndicesService indicesService;
    private final ProjectResolver projectResolver;
    private final CachedShardSuggestService suggestService;
    private final OwnerBackedSuggestService ownerSuggestService;

    @Inject
    public TransportEsTokSuggestAction(
            ClusterService clusterService,
            TransportService transportService,
            ActionFilters actionFilters,
            ProjectResolver projectResolver,
            IndexNameExpressionResolver indexNameExpressionResolver,
            IndicesService indicesService) {
        this(
                clusterService,
                transportService,
                actionFilters,
                projectResolver,
                indexNameExpressionResolver,
                indicesService,
                new CachedShardSuggestService(),
                new OwnerBackedSuggestService());
    }

    TransportEsTokSuggestAction(
            ClusterService clusterService,
            TransportService transportService,
            ActionFilters actionFilters,
            ProjectResolver projectResolver,
            IndexNameExpressionResolver indexNameExpressionResolver,
            IndicesService indicesService,
            CachedShardSuggestService suggestService,
            OwnerBackedSuggestService ownerSuggestService) {
        super(
                EsTokSuggestAction.NAME,
                clusterService,
                transportService,
                actionFilters,
                indexNameExpressionResolver,
                EsTokSuggestRequest::new,
                ShardEsTokSuggestRequest::new,
                transportService.getThreadPool().executor(ThreadPool.Names.SEARCH));
        this.indicesService = indicesService;
        this.projectResolver = projectResolver;
        this.suggestService = suggestService;
        this.ownerSuggestService = ownerSuggestService;
    }

    @Override
    protected EsTokSuggestResponse newResponse(
            EsTokSuggestRequest request,
            AtomicReferenceArray<?> shardsResponses,
            ClusterState clusterState) {
        int successfulShards = 0;
        int failedShards = 0;
        int cacheHitCount = 0;
        List<DefaultShardOperationFailedException> shardFailures = null;
        boolean ownerRequest = ownerSuggestService.supports(request.limitedFields())
            && shouldUseOwnerSuggest(normalizeMode(request.mode()));
        Map<String, AggregatedOption> aggregatedOptions = new HashMap<>();

        for (int i = 0; i < shardsResponses.length(); i++) {
            Object shardResponse = shardsResponses.get(i);
            if (shardResponse == null) {
                continue;
            }
            if (shardResponse instanceof BroadcastShardOperationFailedException exception) {
                failedShards++;
                if (shardFailures == null) {
                    shardFailures = new ArrayList<>();
                }
                shardFailures.add(new DefaultShardOperationFailedException(exception));
                continue;
            }

            ShardEsTokSuggestResponse response = (ShardEsTokSuggestResponse) shardResponse;
            successfulShards++;
            if (response.cacheHit()) {
                cacheHitCount++;
            }
            for (EsTokSuggestOption option : response.options()) {
                AggregatedOption aggregated = aggregatedOptions.computeIfAbsent(
                        option.text(),
                        ignored -> new AggregatedOption(option.text(), option.type(), ownerRequest));
                aggregated.add(option.docFreq(), option.score(), option.shardCount(), option.type());
            }
        }

        List<EsTokSuggestOption> merged = aggregatedOptions.values().stream()
                .map(AggregatedOption::toOption)
                .sorted(Comparator
                .comparingDouble(EsTokSuggestOption::score).reversed()
                .thenComparing(Comparator.comparingInt(EsTokSuggestOption::docFreq).reversed())
                        .thenComparing(Comparator.comparingInt(EsTokSuggestOption::shardCount).reversed())
                        .thenComparing(EsTokSuggestOption::text))
                .limit(request.size())
                .toList();

        return new EsTokSuggestResponse(
                request.text(),
                request.mode(),
                request.limitedFields(),
                merged,
                cacheHitCount,
                shardsResponses.length(),
                successfulShards,
                failedShards,
                shardFailures);
    }

    @Override
    protected ShardEsTokSuggestRequest newShardRequest(int numShards, ShardRouting shard, EsTokSuggestRequest request) {
        return new ShardEsTokSuggestRequest(shard.shardId(), request);
    }

    @Override
    protected ShardEsTokSuggestResponse readShardResponse(StreamInput in) throws IOException {
        return new ShardEsTokSuggestResponse(in);
    }

    @Override
    protected ShardEsTokSuggestResponse shardOperation(ShardEsTokSuggestRequest request, Task task) throws IOException {
        IndexService indexService = indicesService.indexServiceSafe(request.shardId().getIndex());
        IndexShard indexShard = indexService.getShard(request.shardId().id());
        List<String> suggestFields = resolveSuggestFields(indexService, request.fields(), request.usePinyin());
        List<String> associateFields = resolveAssociateFields(indexService, request.fields());
        try (Engine.Searcher searcher = indexShard.acquireSearcher("es_tok_suggest")) {
            IndexReader reader = searcher.getIndexReader();
            if (request.prewarmPinyin()) {
                suggestService.prewarmFields(
                    reader,
                    mergeWarmupFields(suggestFields, associateFields),
                    pinyinWarmupFields(suggestFields));
                if (request.text() == null || request.text().isBlank()) {
                    return new ShardEsTokSuggestResponse(request.shardId(), List.of(), false);
                }
            }
            LuceneIndexSuggester.CompletionConfig completionConfig = new LuceneIndexSuggester.CompletionConfig(
                request.size(),
                request.scanLimit(),
                request.minPrefixLength(),
                request.minCandidateLength(),
                request.allowCompactBigrams(),
                request.usePinyin());
            LuceneIndexSuggester.CorrectionConfig correctionConfig = new LuceneIndexSuggester.CorrectionConfig(
                request.correctionRareDocFreq(),
                effectiveCorrectionMinLength(request),
                request.correctionMaxEdits(),
                request.correctionPrefixLength(),
                request.size(),
                1,
                0.5f,
                request.usePinyin());

            ShardSuggestExecution execution = executeSuggest(
                searcher,
                indexService,
                reader,
                request,
                suggestFields,
                associateFields,
                completionConfig,
                correctionConfig);

            List<EsTokSuggestOption> options = execution.options().stream()
                .map(candidate -> new EsTokSuggestOption(candidate.text(), candidate.docFreq(), candidate.score(), candidate.type(), 1))
                .toList();
            return new ShardEsTokSuggestResponse(request.shardId(), options, execution.cacheHit());
        }
    }

        private ShardSuggestExecution executeSuggest(
            Engine.Searcher searcher,
            IndexService indexService,
            IndexReader reader,
            ShardEsTokSuggestRequest request,
                List<String> suggestFields,
            List<String> associateFields,
            LuceneIndexSuggester.CompletionConfig completionConfig,
            LuceneIndexSuggester.CorrectionConfig correctionConfig) throws IOException {
        String mode = normalizeMode(request.mode());
        if (ownerSuggestService.supports(request.fields())
            && shouldUseOwnerSuggest(mode)) {
            if (request.usePinyin() && isAsciiAlphaNumericQuery(request.text())) {
                CachedShardSuggestService.SuggestResult result = suggestService.suggest(
                    reader,
                    "prefix",
                    suggestFields,
                    request.text(),
                    completionConfig,
                    correctionConfig,
                    request.useCache());
                return new ShardSuggestExecution(
                    ownerSuggestService.rerankOwnerCandidates(
                        searcher,
                        indexService,
                        request.text(),
                        result.options(),
                        request.size(),
                        mode),
                    result.cacheHit());
            }
            return new ShardSuggestExecution(
                ownerSuggestService.suggestOwners(
                    searcher,
                    indexService,
                    suggestFields,
                    request.text(),
                    request.size(),
                    mode),
                false);
        }
        if ("associate".equals(mode)) {
            CachedShardSuggestService.SuggestResult result = suggestService.suggest(
                reader,
                "next_token",
                associateFields,
                request.text(),
                completionConfig,
                correctionConfig,
                request.useCache());
            return new ShardSuggestExecution(
                retagSuggestions(result.options(), "associate"),
                result.cacheHit());
        }
        if ("auto".equals(mode)) {
            return executeAutoSuggest(
                reader,
                indexService,
                request,
                suggestFields,
                associateFields,
                completionConfig,
                correctionConfig);
        }

        CachedShardSuggestService.SuggestResult result = suggestService.suggest(
            reader,
            mode,
            suggestFields,
            request.text(),
            completionConfig,
            correctionConfig,
            request.useCache());
        return new ShardSuggestExecution(result.options(), result.cacheHit());
        }

        private ShardSuggestExecution executeAutoSuggest(
            IndexReader reader,
            IndexService indexService,
            ShardEsTokSuggestRequest request,
            List<String> suggestFields,
            List<String> associateFields,
            LuceneIndexSuggester.CompletionConfig completionConfig,
            LuceneIndexSuggester.CorrectionConfig correctionConfig) throws IOException {
        ShardSuggestExecution primary = executeAutoSuggestForText(
            reader,
            request,
            request.text(),
            suggestFields,
            associateFields,
            completionConfig,
            correctionConfig,
            true);
        if (!shouldRunLongTextFallback(request.text(), primary.options(), request.size())) {
            return primary;
        }

        Map<String, AutoAccumulator> merged = new HashMap<>();
        mergeAutoOptions(merged, primary.options(), 1.0f);
        boolean cacheHit = primary.cacheHit();
        List<String> fallbackTexts = buildFallbackTexts(indexService, suggestFields, request.text());
        for (int index = 0; index < fallbackTexts.size(); index++) {
            String fallbackText = fallbackTexts.get(index);
            ShardSuggestExecution fallback = executeAutoSuggestForText(
                reader,
                request,
                fallbackText,
                suggestFields,
                associateFields,
                completionConfig,
                correctionConfig,
                false);
            cacheHit = cacheHit || fallback.cacheHit();
            float variantWeight = index == 0 ? 0.78f : 0.62f;
            mergeAutoOptions(merged, fallback.options(), variantWeight);
        }

        List<LuceneIndexSuggester.SuggestionOption> mergedOptions = merged.values().stream()
            .sorted(AutoAccumulator.ORDER)
            .limit(request.size())
            .map(AutoAccumulator::toOption)
            .toList();
        return new ShardSuggestExecution(mergedOptions, cacheHit);
        }

        private ShardSuggestExecution executeAutoSuggestForText(
            IndexReader reader,
            ShardEsTokSuggestRequest request,
            String text,
            List<String> suggestFields,
            List<String> associateFields,
            LuceneIndexSuggester.CompletionConfig completionConfig,
            LuceneIndexSuggester.CorrectionConfig correctionConfig,
            boolean includeCorrection) throws IOException {
        CachedShardSuggestService.SuggestResult prefixResult = suggestService.suggest(
            reader,
            "prefix",
            suggestFields,
            text,
            completionConfig,
            correctionConfig,
            request.useCache());
        CachedShardSuggestService.SuggestResult correctionResult = includeCorrection
            ? suggestService.suggest(
                reader,
                "correction",
                suggestFields,
                text,
                completionConfig,
                correctionConfig,
                request.useCache())
            : new CachedShardSuggestService.SuggestResult(List.of(), false);
        List<LuceneIndexSuggester.SuggestionOption> associateOptions = shouldRunAssociateInAuto(
            text,
            request.usePinyin(),
            prefixResult.options(),
            correctionResult.options())
            ? retagSuggestions(
                suggestService.suggest(
                    reader,
                    "next_token",
                    associateFields,
                    text,
                    completionConfig,
                    correctionConfig,
                    request.useCache()).options(),
                "associate")
            : List.of();
        return new ShardSuggestExecution(
            mergeAuto(prefixResult.options(), correctionResult.options(), associateOptions, request.size(), text, request.usePinyin()),
            prefixResult.cacheHit() || correctionResult.cacheHit());
        }

        private static List<String> resolveAssociateFields(IndexService indexService, List<String> requestFields) {
        if (requestFields == null || requestFields.isEmpty()) {
            return requestFields;
        }

        MappingLookup mappingLookup = indexService.mapperService().mappingLookup();
        Set<String> mappedFields = mappingLookup.getFullNameToFieldType().keySet();
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String field : requestFields) {
            if (field == null || field.isBlank()) {
                continue;
            }

            String sourceField = field;
            if (field.endsWith(".words")) {
                sourceField = field.substring(0, field.length() - ".words".length());
            } else if (field.endsWith(".suggest")) {
                sourceField = field.substring(0, field.length() - ".suggest".length());
            }

            String associateField = sourceField + ".assoc";
            if (mappedFields.contains(associateField)) {
                resolved.add(associateField);
            } else {
                resolved.add(field);
            }
        }
        maybeAddDescWordsField(mappedFields, resolved, requestFields);
        return List.copyOf(resolved);
        }

        private static void maybeAddDescWordsField(
            Set<String> mappedFields,
            LinkedHashSet<String> resolved,
            List<String> requestFields) {
        if (!mappedFields.contains("desc.words")) {
            return;
        }
        for (String field : requestFields) {
            String sourceField = sourceField(field);
            if ("title".equals(sourceField) || "tags".equals(sourceField) || "pages.parts".equals(sourceField) || "desc".equals(sourceField)) {
                resolved.add("desc.words");
                return;
            }
        }
        }

        private static String sourceField(String field) {
        if (field == null || field.isBlank()) {
            return "";
        }
        if (field.endsWith(".keyword")) {
            return field.substring(0, field.length() - ".keyword".length());
        }
        if (field.endsWith(".words")) {
            return field.substring(0, field.length() - ".words".length());
        }
        if (field.endsWith(".suggest")) {
            return field.substring(0, field.length() - ".suggest".length());
        }
        if (field.endsWith(".assoc")) {
            return field.substring(0, field.length() - ".assoc".length());
        }
        return field;
        }

        private static List<String> resolveSuggestFields(IndexService indexService, List<String> requestFields, boolean usePinyin) {
        if (!usePinyin || requestFields == null || requestFields.isEmpty()) {
            return requestFields;
        }

        MappingLookup mappingLookup = indexService.mapperService().mappingLookup();
        Set<String> mappedFields = mappingLookup.getFullNameToFieldType().keySet();
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String field : requestFields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            if (field.endsWith(".keyword")) {
                String suggestField = field.substring(0, field.length() - ".keyword".length()) + ".suggest";
                if (mappedFields.contains(suggestField)) {
                    resolved.add(suggestField);
                    continue;
                }
            }
            if (field.endsWith(".words")) {
                String suggestField = field.substring(0, field.length() - ".words".length()) + ".suggest";
                if (mappedFields.contains(suggestField)) {
                    resolved.add(suggestField);
                    continue;
                }
            }
            resolved.add(field);
        }
        return List.copyOf(resolved);
        }

        private static String normalizeMode(String mode) {
        return mode;
        }

        private static boolean shouldUseOwnerSuggest(String mode) {
        return "prefix".equals(mode) || "auto".equals(mode) || "associate".equals(mode);
        }

        private static boolean isAsciiAlphaNumericQuery(String text) {
        return text != null
            && text.isBlank() == false
            && text.chars().allMatch(ch -> ch < 128 && Character.isLetterOrDigit(ch));
        }

        private static List<LuceneIndexSuggester.SuggestionOption> retagSuggestions(
            List<LuceneIndexSuggester.SuggestionOption> options,
            String type) {
        return options.stream()
            .map(option -> new LuceneIndexSuggester.SuggestionOption(
                option.text(),
                option.docFreq(),
                option.score(),
                type))
            .toList();
        }

        private static List<String> mergeWarmupFields(List<String> suggestFields, List<String> associateFields) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (suggestFields != null) {
            merged.addAll(suggestFields);
        }
        if (associateFields != null) {
            merged.addAll(associateFields);
        }
        return List.copyOf(merged);
        }

        private static List<String> pinyinWarmupFields(List<String> suggestFields) {
        if (suggestFields == null || suggestFields.isEmpty()) {
            return List.of();
        }
        return suggestFields.stream()
            .filter(field -> field.endsWith(".assoc") == false)
            .toList();
        }

        private static List<LuceneIndexSuggester.SuggestionOption> mergeAuto(
            List<LuceneIndexSuggester.SuggestionOption> prefixOptions,
            List<LuceneIndexSuggester.SuggestionOption> correctionOptions,
            List<LuceneIndexSuggester.SuggestionOption> associateOptions,
            int size,
            String text,
            boolean usePinyin) {
        Map<String, AutoAccumulator> merged = new HashMap<>();
        float prefixWeight = autoPrefixWeight(text, usePinyin);
        float correctionWeight = autoCorrectionWeight(text, usePinyin);
        float associateWeight = autoAssociateWeight(text, usePinyin);
        mergeAutoBranch(merged, prefixOptions, prefixWeight, "prefix");
        mergeAutoBranch(merged, correctionOptions, correctionWeight, "correction");
        mergeAutoBranch(merged, associateOptions, associateWeight, "associate");
        return merged.values().stream()
            .sorted(AutoAccumulator.ORDER)
            .limit(size)
            .map(AutoAccumulator::toOption)
            .toList();
        }

        private static void mergeAutoOptions(
            Map<String, AutoAccumulator> merged,
            List<LuceneIndexSuggester.SuggestionOption> options,
            float variantWeight) {
        if (options.isEmpty() || variantWeight <= 0.0f) {
            return;
        }
        Map<String, List<LuceneIndexSuggester.SuggestionOption>> byType = new HashMap<>();
        for (LuceneIndexSuggester.SuggestionOption option : options) {
            byType.computeIfAbsent(option.type(), ignored -> new ArrayList<>()).add(option);
        }
        for (Map.Entry<String, List<LuceneIndexSuggester.SuggestionOption>> entry : byType.entrySet()) {
            mergeAutoBranch(merged, entry.getValue(), variantWeight, entry.getKey());
        }
        }

        private static boolean shouldRunLongTextFallback(
            String text,
            List<LuceneIndexSuggester.SuggestionOption> options,
            int requestedSize) {
        if (!isLongTextQuery(text)) {
            return false;
        }
        return options.size() < Math.min(2, requestedSize);
        }

        private static boolean isLongTextQuery(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = collapseWhitespace(text);
        return normalized.codePointCount(0, normalized.length()) >= 12 || normalized.chars().anyMatch(Character::isWhitespace);
        }

        private static List<String> buildFallbackTexts(IndexService indexService, List<String> suggestFields, String text) throws IOException {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        String collapsed = collapseWhitespace(text);
        String compacted = compactHanWhitespace(collapsed);
        for (String analyzedVariant : analyzedFallbackTexts(indexService, suggestFields, compacted)) {
            variants.add(analyzedVariant);
        }
        if (!compacted.equals(collapsed)) {
            variants.add(compacted);
        }
        if (compacted.codePointCount(0, compacted.length()) > 12) {
            variants.add(sliceLeadingCodePoints(compacted, 12));
        }
        if (compacted.codePointCount(0, compacted.length()) > 8) {
            variants.add(sliceLeadingCodePoints(compacted, 8));
        }
        if (compacted.codePointCount(0, compacted.length()) > 6) {
            variants.add(sliceLeadingCodePoints(compacted, 6));
        }
        if (compacted.codePointCount(0, compacted.length()) > 10) {
            variants.add(sliceTrailingCodePoints(compacted, 10));
        }
        String firstSpan = firstFallbackSpan(compacted);
        if (!firstSpan.isBlank()) {
            variants.add(firstSpan);
        }
        variants.remove(collapseWhitespace(text));
        return variants.stream().limit(MAX_LONG_TEXT_FALLBACKS).toList();
        }

        private static List<String> analyzedFallbackTexts(
            IndexService indexService,
            List<String> suggestFields,
            String text) throws IOException {
        Analyzer analyzer = resolveFallbackAnalyzer(indexService, suggestFields);
        if (analyzer == null) {
            return List.of();
        }
        List<String> tokens = analyzeFallbackTokens(analyzer, fallbackAnalyzeField(suggestFields), text);
        if (tokens.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(tokens.get(0));
        if (tokens.size() > 1) {
            variants.add(joinFallbackTokens(tokens.subList(0, Math.min(2, tokens.size()))));
        }
        List<String> byLength = new ArrayList<>(tokens);
        byLength.sort(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo));
        variants.add(byLength.get(0));
        if (byLength.size() > 1) {
            variants.add(joinFallbackTokens(byLength.subList(0, Math.min(2, byLength.size()))));
        }
        return List.copyOf(variants);
        }

        private static Analyzer resolveFallbackAnalyzer(IndexService indexService, List<String> suggestFields) {
        if (indexService == null) {
            return Lucene.KEYWORD_ANALYZER;
        }
        String field = fallbackAnalyzeField(suggestFields);
        if (field.isBlank()) {
            return Lucene.KEYWORD_ANALYZER;
        }
        MappedFieldType fieldType = indexService.mapperService().fieldType(field);
        if (fieldType == null) {
            return Lucene.KEYWORD_ANALYZER;
        }
        return fieldType.getTextSearchInfo().searchAnalyzer();
        }

        private static String fallbackAnalyzeField(List<String> suggestFields) {
        if (suggestFields == null || suggestFields.isEmpty()) {
            return "";
        }
        return suggestFields.get(0);
        }

        private static List<String> analyzeFallbackTokens(Analyzer analyzer, String field, String text) throws IOException {
        if (analyzer == null || text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        try (TokenStream tokenStream = analyzer.tokenStream(field, text)) {
            CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                String token = termAttribute.toString().trim().toLowerCase(java.util.Locale.ROOT);
                if (!token.isBlank()) {
                    tokens.add(token);
                }
            }
            tokenStream.end();
        }
        return List.copyOf(tokens);
        }

        private static String joinFallbackTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return "";
        }
        boolean hasNonAscii = tokens.stream().flatMapToInt(String::chars).anyMatch(ch -> ch >= 128);
        return hasNonAscii ? String.join("", tokens) : String.join(" ", tokens);
        }

        private static String firstFallbackSpan(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] parts = collapseWhitespace(text).split(" ");
        if (parts.length == 0) {
            return "";
        }
        if (parts[0].codePointCount(0, parts[0].length()) <= 10) {
            return parts[0];
        }
        return sliceLeadingCodePoints(parts[0], 6);
        }

        private static String collapseWhitespace(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return String.join(" ", text.trim().split("\\s+"));
        }

        private static String compactHanWhitespace(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        int[] codePoints = text.codePoints().toArray();
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            if (Character.isWhitespace(codePoint)) {
                int previous = previousNonWhitespace(codePoints, index - 1);
                int next = nextNonWhitespace(codePoints, index + 1);
                if (previous >= 0 && next >= 0 && (isHanCodePoint(previous) || isHanCodePoint(next))) {
                    continue;
                }
                builder.append(' ');
                continue;
            }
            builder.appendCodePoint(codePoint);
        }
        return collapseWhitespace(builder.toString());
        }

        private static int previousNonWhitespace(int[] codePoints, int index) {
        for (int offset = index; offset >= 0; offset--) {
            if (!Character.isWhitespace(codePoints[offset])) {
                return codePoints[offset];
            }
        }
        return -1;
        }

        private static int nextNonWhitespace(int[] codePoints, int index) {
        for (int offset = index; offset < codePoints.length; offset++) {
            if (!Character.isWhitespace(codePoints[offset])) {
                return codePoints[offset];
            }
        }
        return -1;
        }

        private static boolean isHanCodePoint(int codePoint) {
        return codePoint >= 0 && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
        }

        private static String sliceLeadingCodePoints(String text, int count) {
        int[] codePoints = text.codePoints().limit(count).toArray();
        return codePoints.length == 0 ? "" : new String(codePoints, 0, codePoints.length);
        }

        private static String sliceTrailingCodePoints(String text, int count) {
        int[] codePoints = text.codePoints().toArray();
        int start = Math.max(0, codePoints.length - count);
        return codePoints.length == 0 ? "" : new String(codePoints, start, codePoints.length - start);
        }

        private static boolean shouldIncludeAssociateInAuto(String text, boolean usePinyin) {
        return !(usePinyin && PinyinSupport.containsChinese(text));
        }

        private static boolean shouldRunAssociateInAuto(
            String text,
            boolean usePinyin,
            List<LuceneIndexSuggester.SuggestionOption> prefixOptions,
            List<LuceneIndexSuggester.SuggestionOption> correctionOptions) {
        if (!shouldIncludeAssociateInAuto(text, usePinyin)) {
            return false;
        }
        if (hasStrongDirectAutoCoverage(text, prefixOptions, correctionOptions)) {
            return false;
        }
        return true;
        }

        private static boolean hasStrongDirectAutoCoverage(
            String text,
            List<LuceneIndexSuggester.SuggestionOption> prefixOptions,
            List<LuceneIndexSuggester.SuggestionOption> correctionOptions) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.trim().toLowerCase(java.util.Locale.ROOT);
        boolean asciiOnly = normalized.chars().allMatch(ch -> ch < 128);
        boolean hasWhitespace = normalized.chars().anyMatch(Character::isWhitespace);
        if (!prefixOptions.isEmpty()) {
            if (asciiOnly) {
                return true;
            }
            if (hasWhitespace) {
                return true;
            }
        }
        return prefixOptions.isEmpty() && !correctionOptions.isEmpty() && asciiOnly && hasWhitespace;
        }

        private static int effectiveCorrectionMinLength(ShardEsTokSuggestRequest request) {
        if (!request.usePinyin() || !PinyinSupport.containsChinese(request.text())) {
            return request.correctionMinLength();
        }
        return Math.min(request.correctionMinLength(), 2);
        }

        private static float autoPrefixWeight(String text, boolean usePinyin) {
        if (usePinyin && PinyinSupport.isPinyinLikeQuery(text)) {
            return 1.25f;
        }
        return 1.0f;
        }

        private static float autoCorrectionWeight(String text, boolean usePinyin) {
        if (usePinyin && PinyinSupport.isPinyinLikeQuery(text)) {
            return 1.18f;
        }
        return 0.96f;
        }

        private static float autoAssociateWeight(String text, boolean usePinyin) {
        if (usePinyin && PinyinSupport.isPinyinLikeQuery(text)) {
            return 0.0f;
        }
        return 0.9f;
        }

        private static void mergeAutoBranch(
            Map<String, AutoAccumulator> merged,
            List<LuceneIndexSuggester.SuggestionOption> options,
            float weight,
            String sourceType) {
        if (options.isEmpty() || weight <= 0.0f) {
            return;
        }
        float topScore = Math.max(1.0f, options.get(0).score());
        for (int index = 0; index < options.size(); index++) {
            LuceneIndexSuggester.SuggestionOption option = options.get(index);
            if (option.score() <= 0.0f) {
                continue;
            }
            float branchScore = weight
                * ((Math.max(0.0f, option.score()) / topScore) * 100.0f + (float) (Math.log1p(option.docFreq()) * 3.0d))
                * Math.max(0.3f, 1.0f - (index * 0.08f));
            if (branchScore <= 0.0f) {
                continue;
            }
            merged.computeIfAbsent(option.text(), AutoAccumulator::new)
                .add(option.docFreq(), branchScore, sourceType);
        }
        }

        private record ShardSuggestExecution(List<LuceneIndexSuggester.SuggestionOption> options, boolean cacheHit) {
        }

        private static final class AutoAccumulator {
        private static final Comparator<AutoAccumulator> ORDER = Comparator
            .comparingDouble(AutoAccumulator::score).reversed()
            .thenComparing(Comparator.comparingInt(AutoAccumulator::docFreq).reversed())
            .thenComparing(AutoAccumulator::text);

        private final String text;
        private final LinkedHashSet<String> sources = new LinkedHashSet<>();
        private final Map<String, Float> sourceScores = new HashMap<>();
        private int docFreq;
        private float score;

        private AutoAccumulator(String text) {
            this.text = text;
        }

        private void add(int docFreq, float score, String source) {
            this.docFreq = Math.max(this.docFreq, docFreq);
            this.score += score;
            this.sources.add(source);
            this.sourceScores.merge(source, score, Float::sum);
        }

        private float score() {
            return score + Math.max(0, sources.size() - 1) * 5.0f;
        }

        private int docFreq() {
            return docFreq;
        }

        private String text() {
            return text;
        }

        private String dominantSource() {
            return sourceScores.entrySet().stream()
                .max(Map.Entry.<String, Float>comparingByValue()
                    .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElse("prefix");
        }

        private LuceneIndexSuggester.SuggestionOption toOption() {
            return new LuceneIndexSuggester.SuggestionOption(text, docFreq, score(), dominantSource());
        }
        }

    @Override
    protected List<ShardIterator> shards(ClusterState clusterState, EsTokSuggestRequest request, String[] concreteIndices) {
        ProjectState projectState = projectResolver.getProjectState(clusterState);
        Map<String, java.util.Set<String>> routingMap = indexNameExpressionResolver.resolveSearchRouting(
                projectState.metadata(),
                null,
                request.indices());
        return clusterService.operationRouting().searchShards(projectState, concreteIndices, routingMap, "_local");
    }

    @Override
    protected ClusterBlockException checkGlobalBlock(ClusterState state, EsTokSuggestRequest request) {
        return state.blocks().globalBlockedException(projectResolver.getProjectId(), ClusterBlockLevel.READ);
    }

    @Override
    protected ClusterBlockException checkRequestBlock(ClusterState state, EsTokSuggestRequest request, String[] concreteIndices) {
        return state.blocks().indicesBlockedException(projectResolver.getProjectId(), ClusterBlockLevel.READ, concreteIndices);
    }

    private static final class AggregatedOption {
        private final String text;
        private final Map<String, Float> typeScores = new HashMap<>();
        private final boolean ownerRequest;
        private int docFreq;
        private float score;
        private float maxScore;
        private int shardCount;

        private AggregatedOption(String text, String type, boolean ownerRequest) {
            this.text = text;
            this.ownerRequest = ownerRequest;
            this.typeScores.put(type, 0.0f);
        }

        private void add(int docFreq, float score, int shardCount, String type) {
            this.docFreq += docFreq;
            this.score += score;
            this.maxScore = Math.max(this.maxScore, score);
            this.shardCount += shardCount;
            this.typeScores.merge(type, score, Float::sum);
        }

        private float mergedScore() {
            if (!ownerRequest) {
                return score;
            }
            float residual = Math.max(0.0f, score - maxScore);
            return maxScore + (residual * 0.08f);
        }

        private String dominantType() {
            return typeScores.entrySet().stream()
                .max(Map.Entry.<String, Float>comparingByValue()
                    .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElse("prefix");
        }

        private EsTokSuggestOption toOption() {
            return new EsTokSuggestOption(text, docFreq, mergedScore(), dominantType(), shardCount);
        }
    }
}