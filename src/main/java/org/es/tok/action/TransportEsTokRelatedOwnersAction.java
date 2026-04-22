package org.es.tok.action;

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
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.es.tok.suggest.PinyinSupport;
import org.es.tok.suggest.SourceBackedRelatedOwnersService;
import org.es.tok.text.TextNormalization;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.regex.Pattern;

public class TransportEsTokRelatedOwnersAction extends TransportBroadcastAction<
        EsTokRelatedOwnersRequest,
        EsTokRelatedOwnersResponse,
        ShardEsTokRelatedOwnersRequest,
        ShardEsTokRelatedOwnersResponse> {
    private static final float OWNER_INTENT_TOP_PROMOTION_MARGIN = 12000.0f;
    private static final double OWNER_INTENT_MIN_MATCH_SCORE = 4.5d;
    private static final double OWNER_INTENT_MIN_GAP_SCORE = 1.0d;
    private static final Pattern OWNER_INTENT_PART_PATTERN = Pattern.compile("[\\p{IsHan}]+|[A-Za-z]+|\\d+");

    private final ClusterService clusterService;
    private final IndicesService indicesService;
    private final IndexNameExpressionResolver indexNameExpressionResolver;
    private final ProjectResolver projectResolver;
    private final SourceBackedRelatedOwnersService relatedOwnersService;

    @Inject
    public TransportEsTokRelatedOwnersAction(
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
                new SourceBackedRelatedOwnersService());
    }

    TransportEsTokRelatedOwnersAction(
            ClusterService clusterService,
            TransportService transportService,
            ActionFilters actionFilters,
            ProjectResolver projectResolver,
            IndexNameExpressionResolver indexNameExpressionResolver,
            IndicesService indicesService,
            SourceBackedRelatedOwnersService relatedOwnersService) {
        super(
                EsTokRelatedOwnersAction.NAME,
                clusterService,
                transportService,
                actionFilters,
                indexNameExpressionResolver,
                EsTokRelatedOwnersRequest::new,
                ShardEsTokRelatedOwnersRequest::new,
                transportService.getThreadPool().executor(ThreadPool.Names.SEARCH));
            this.clusterService = clusterService;
        this.indicesService = indicesService;
            this.indexNameExpressionResolver = indexNameExpressionResolver;
        this.projectResolver = projectResolver;
        this.relatedOwnersService = relatedOwnersService;
    }

    @Override
    protected EsTokRelatedOwnersResponse newResponse(
            EsTokRelatedOwnersRequest request,
            AtomicReferenceArray<?> shardsResponses,
            ClusterState clusterState) {
        int successfulShards = 0;
        int failedShards = 0;
        List<DefaultShardOperationFailedException> shardFailures = null;
        Map<Long, AggregatedOwner> aggregatedOwners = new HashMap<>();

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

            ShardEsTokRelatedOwnersResponse response = (ShardEsTokRelatedOwnersResponse) shardResponse;
            successfulShards++;
            for (EsTokRelatedOwnerOption owner : response.owners()) {
                aggregatedOwners.computeIfAbsent(owner.mid(), ignored -> new AggregatedOwner(owner.mid()))
                        .add(owner);
            }
        }

        List<EsTokRelatedOwnerOption> merged = aggregatedOwners.values().stream()
                .map(AggregatedOwner::toOption)
                .toList();
        merged = selectMergedOwners(request.text(), merged, request.size());

        return new EsTokRelatedOwnersResponse(
                request.text(),
                request.limitedFields(),
                merged,
                shardsResponses.length(),
                successfulShards,
                failedShards,
                shardFailures);
    }

    @Override
    protected ShardEsTokRelatedOwnersRequest newShardRequest(int numShards, ShardRouting shard, EsTokRelatedOwnersRequest request) {
        return new ShardEsTokRelatedOwnersRequest(shard.shardId(), request);
    }

    @Override
    protected ShardEsTokRelatedOwnersResponse readShardResponse(StreamInput in) throws IOException {
        return new ShardEsTokRelatedOwnersResponse(in);
    }

    @Override
    protected ShardEsTokRelatedOwnersResponse shardOperation(ShardEsTokRelatedOwnersRequest request, Task task) throws IOException {
        IndexService indexService = indicesService.indexServiceSafe(request.shardId().getIndex());
        IndexShard indexShard = indexService.getShard(request.shardId().id());
        List<String> searchFields = resolveSearchFields(indexService, request.fields(), request.usePinyin());
        try (org.elasticsearch.index.engine.Engine.Searcher searcher = indexShard.acquireSearcher("es_tok_related_owners")) {
            List<EsTokRelatedOwnerOption> owners = relatedOwnersService.searchRelatedOwners(
                            searcher,
                            indexService,
                            searchFields,
                            request.text(),
                            request.size(),
                            request.scanLimit())
                    .stream()
                    .map(result -> new EsTokRelatedOwnerOption(result.mid(), result.name(), result.docFreq(), result.score(), 1))
                    .toList();
            return new ShardEsTokRelatedOwnersResponse(request.shardId(), owners);
        }
    }

    private static List<String> resolveSearchFields(IndexService indexService, List<String> requestFields, boolean usePinyin) {
        if (requestFields == null || requestFields.isEmpty()) {
            return requestFields;
        }

        Set<String> mappedFields = indexService.mapperService().mappingLookup().getFullNameToFieldType().keySet();
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String field : requestFields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            if (field.endsWith(".words")) {
                String suggestField = field.substring(0, field.length() - ".words".length()) + ".suggest";
                if (usePinyin && mappedFields.contains(suggestField)) {
                    resolved.add(suggestField);
                    continue;
                }
            }
            resolved.add(field);
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

    @Override
    protected List<ShardIterator> shards(ClusterState clusterState, EsTokRelatedOwnersRequest request, String[] concreteIndices) {
        ProjectState projectState = projectResolver.getProjectState(clusterState);
        Map<String, java.util.Set<String>> routingMap = indexNameExpressionResolver.resolveSearchRouting(
                projectState.metadata(),
                null,
                request.indices());
        return clusterService.operationRouting().searchShards(projectState, concreteIndices, routingMap, "_local");
    }

    @Override
    protected ClusterBlockException checkGlobalBlock(ClusterState state, EsTokRelatedOwnersRequest request) {
        return state.blocks().globalBlockedException(projectResolver.getProjectId(), ClusterBlockLevel.READ);
    }

    @Override
    protected ClusterBlockException checkRequestBlock(ClusterState state, EsTokRelatedOwnersRequest request, String[] concreteIndices) {
        return state.blocks().indicesBlockedException(projectResolver.getProjectId(), ClusterBlockLevel.READ, concreteIndices);
    }

    static List<EsTokRelatedOwnerOption> selectMergedOwners(String text, List<EsTokRelatedOwnerOption> options, int size) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }

        List<EsTokRelatedOwnerOption> adjusted = maybePromoteOwnerIntentCandidate(text, options);
        return adjusted.stream()
                .sorted(Comparator
                        .comparingDouble(EsTokRelatedOwnerOption::score).reversed()
                        .thenComparing(Comparator.comparingInt(EsTokRelatedOwnerOption::docFreq).reversed())
                        .thenComparing(Comparator.comparingInt(EsTokRelatedOwnerOption::shardCount).reversed())
                        .thenComparing(EsTokRelatedOwnerOption::name))
                .limit(size)
                .toList();
    }

    private static List<EsTokRelatedOwnerOption> maybePromoteOwnerIntentCandidate(
            String text,
            List<EsTokRelatedOwnerOption> options) {
        if (!isOwnerIntentLikeQuery(text) || options.isEmpty()) {
            return List.copyOf(options);
        }

        double bestMatchScore = Double.NEGATIVE_INFINITY;
        double secondBestMatchScore = Double.NEGATIVE_INFINITY;
        EsTokRelatedOwnerOption bestOption = null;
        for (EsTokRelatedOwnerOption option : options) {
            double matchScore = ownerIntentMatchScore(text, option.name());
            if (matchScore > bestMatchScore) {
                secondBestMatchScore = bestMatchScore;
                bestMatchScore = matchScore;
                bestOption = option;
            } else if (matchScore > secondBestMatchScore) {
                secondBestMatchScore = matchScore;
            }
        }

        if (bestOption == null
                || bestMatchScore < OWNER_INTENT_MIN_MATCH_SCORE
                || (secondBestMatchScore > Double.NEGATIVE_INFINITY
                    && (bestMatchScore - secondBestMatchScore) < OWNER_INTENT_MIN_GAP_SCORE)) {
            return List.copyOf(options);
        }

        float topScore = options.stream().map(EsTokRelatedOwnerOption::score).max(Float::compare).orElse(0.0f);
        float promotedScore = Math.max(bestOption.score(), topScore + OWNER_INTENT_TOP_PROMOTION_MARGIN);
        List<EsTokRelatedOwnerOption> adjusted = new ArrayList<>(options.size());
        for (EsTokRelatedOwnerOption option : options) {
            if (option.mid() == bestOption.mid()) {
                adjusted.add(new EsTokRelatedOwnerOption(
                        option.mid(),
                        option.name(),
                        option.docFreq(),
                        promotedScore,
                        option.shardCount()));
            } else {
                adjusted.add(option);
            }
        }
        return List.copyOf(adjusted);
    }

    private static boolean isOwnerIntentLikeQuery(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (text.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        int codePointLength = text.codePointCount(0, text.length());
        if (codePointLength < 2 || codePointLength > 24) {
            return false;
        }
        boolean hasChinese = PinyinSupport.containsChinese(text);
        boolean hasDigit = text.chars().anyMatch(Character::isDigit);
        boolean hasAsciiLetter = text.chars().anyMatch(ch -> ch < 128 && Character.isLetter(ch));
        return hasDigit
                || (hasChinese && hasAsciiLetter)
                || PinyinSupport.isStrictFullPinyinQuery(text);
    }

    private static double ownerIntentMatchScore(String text, String ownerName) {
        String normalizedQuery = TextNormalization.normalizeOwnerLookupName(text);
        String normalizedOwner = TextNormalization.normalizeOwnerLookupName(ownerName);
        if (normalizedQuery.isBlank() || normalizedOwner.isBlank()) {
            return 0.0d;
        }

        double score = 0.0d;
        if (normalizedOwner.equals(normalizedQuery)) {
            score += 8.0d;
        }
        if (normalizedOwner.startsWith(normalizedQuery)) {
            score += 5.0d;
        }
        List<String> queryParts = OWNER_INTENT_PART_PATTERN.matcher(normalizedQuery)
                .results()
                .map(match -> match.group())
                .filter(part -> !part.isBlank())
                .toList();
        if (queryParts.isEmpty()) {
            return score;
        }

        int searchFrom = 0;
        int matchedParts = 0;
        boolean matchedChinese = false;
        boolean matchedDigits = false;
        for (String part : queryParts) {
            int index = normalizedOwner.indexOf(part, searchFrom);
            if (index < 0) {
                continue;
            }
            matchedParts++;
            searchFrom = index + part.length();
            if (part.chars().allMatch(Character::isDigit)) {
                matchedDigits = true;
                score += 2.2d;
            } else if (PinyinSupport.containsChinese(part)) {
                matchedChinese = true;
                score += index == 0 ? 2.6d : 1.8d;
            } else {
                score += 1.1d;
            }
        }

        if (matchedParts == queryParts.size()) {
            score += 3.5d;
        }
        if (matchedChinese && matchedDigits) {
            score += 2.4d;
        }
        if (PinyinSupport.isStrictFullPinyinQuery(normalizedQuery) && PinyinSupport.fullPinyinPrefixMatch(normalizedQuery, ownerName)) {
            score += 4.0d;
        }
        return score;
    }

    private static final class AggregatedOwner {
        private final long mid;
        private final Map<String, Float> nameScores = new HashMap<>();
        private int docFreq;
        private float score;
        private float maxScore;
        private int shardCount;

        private AggregatedOwner(long mid) {
            this.mid = mid;
        }

        private void add(EsTokRelatedOwnerOption option) {
            docFreq += option.docFreq();
            score += option.score();
            maxScore = Math.max(maxScore, option.score());
            shardCount += option.shardCount();
            nameScores.merge(option.name(), option.score(), Float::sum);
        }

        private String displayName() {
            return nameScores.entrySet().stream()
                    .max(Map.Entry.<String, Float>comparingByValue()
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElse(Long.toString(mid));
        }

        private float mergedScore() {
            float residual = Math.max(0.0f, score - maxScore);
            return maxScore + (residual * 0.08f);
        }

        private EsTokRelatedOwnerOption toOption() {
            return new EsTokRelatedOwnerOption(mid, displayName(), docFreq, mergedScore(), shardCount);
        }
    }
}
