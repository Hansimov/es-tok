package org.es.tok.action;

import org.apache.lucene.index.IndexReader;
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
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.MappingLookup;
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
import org.es.tok.suggest.AutoSuggestTextVariants;
import org.es.tok.suggest.SourceBackedAssociateSuggester;

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

    private final IndicesService indicesService;
    private final ProjectResolver projectResolver;
    private final CachedShardSuggestService suggestService;
    private final OwnerBackedSuggestService ownerSuggestService;
    private final SourceBackedAssociateSuggester associateSuggester;

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
                new OwnerBackedSuggestService(),
                new SourceBackedAssociateSuggester());
    }

    TransportEsTokSuggestAction(
            ClusterService clusterService,
            TransportService transportService,
            ActionFilters actionFilters,
            ProjectResolver projectResolver,
            IndexNameExpressionResolver indexNameExpressionResolver,
            IndicesService indicesService,
            CachedShardSuggestService suggestService,
            OwnerBackedSuggestService ownerSuggestService,
            SourceBackedAssociateSuggester associateSuggester) {
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
        this.associateSuggester = associateSuggester;
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
                responseMode(request.mode()),
                request.limitedFields(),
                merged,
                cacheHitCount,
                shardsResponses.length(),
                successfulShards,
                failedShards,
                shardFailures);
    }

    private static String responseMode(String mode) {
        return normalizeMode(mode);
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
            return new ShardSuggestExecution(
                associateSuggester.suggestAssociate(
                    searcher,
                    indexService,
                    associateFields,
                    request.text(),
                    completionConfig),
                false);
        }
        if ("auto".equals(mode)) {
            return executeAutoSuggest(
                searcher,
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
            Engine.Searcher searcher,
            IndexReader reader,
            IndexService indexService,
            ShardEsTokSuggestRequest request,
            List<String> suggestFields,
            List<String> associateFields,
            LuceneIndexSuggester.CompletionConfig completionConfig,
            LuceneIndexSuggester.CorrectionConfig correctionConfig) throws IOException {
        ShardSuggestExecution primary = executeAutoSuggestForText(
            searcher,
            indexService,
            reader,
            request,
            request.text(),
            suggestFields,
            associateFields,
            completionConfig,
            correctionConfig,
            true);
        if (!AutoSuggestTextVariants.shouldRunLongTextFallback(request.text(), primary.options(), request.size())) {
            return primary;
        }

        Map<String, AutoAccumulator> merged = new HashMap<>();
        mergeAutoOptions(merged, primary.options(), 1.0f);
        boolean cacheHit = primary.cacheHit();
        List<String> fallbackTexts = AutoSuggestTextVariants.buildFallbackTexts(indexService, suggestFields, request.text());
        for (int index = 0; index < fallbackTexts.size(); index++) {
            String fallbackText = fallbackTexts.get(index);
            ShardSuggestExecution fallback = executeAutoSuggestForText(
                searcher,
                indexService,
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
            Engine.Searcher searcher,
            IndexService indexService,
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
        LuceneIndexSuggester.CompletionConfig autoAssociateConfig = new LuceneIndexSuggester.CompletionConfig(
            completionConfig.size(),
            Math.min(completionConfig.scanLimit(), 32),
            completionConfig.minPrefixLength(),
            completionConfig.minCandidateLength(),
            completionConfig.allowCompactBigrams(),
            completionConfig.usePinyin());
        List<LuceneIndexSuggester.SuggestionOption> associateOptions = includeCorrection && shouldRunAssociateInAuto(
            text,
            request.usePinyin(),
            prefixResult.options(),
            correctionResult.options())
            ? associateSuggester.suggestAssociate(
                searcher,
                indexService,
                associateFields,
                text,
                autoAssociateConfig)
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
        if ("semantic".equals(mode)) {
            return "auto";
        }
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
