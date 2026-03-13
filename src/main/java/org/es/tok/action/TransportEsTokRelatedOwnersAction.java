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
import org.es.tok.suggest.SourceBackedRelatedOwnersService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class TransportEsTokRelatedOwnersAction extends TransportBroadcastAction<
        EsTokRelatedOwnersRequest,
        EsTokRelatedOwnersResponse,
        ShardEsTokRelatedOwnersRequest,
        ShardEsTokRelatedOwnersResponse> {

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
                .sorted(Comparator
                        .comparingDouble(EsTokRelatedOwnerOption::score).reversed()
                        .thenComparing(Comparator.comparingInt(EsTokRelatedOwnerOption::docFreq).reversed())
                        .thenComparing(Comparator.comparingInt(EsTokRelatedOwnerOption::shardCount).reversed())
                        .thenComparing(EsTokRelatedOwnerOption::name))
                .limit(request.size())
                .toList();

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
        if (!usePinyin || requestFields == null || requestFields.isEmpty()) {
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
                if (mappedFields.contains(suggestField)) {
                    resolved.add(suggestField);
                    continue;
                }
            }
            resolved.add(field);
        }
        return List.copyOf(resolved);
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
