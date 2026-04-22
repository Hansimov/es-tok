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
import org.es.tok.relations.SourceBackedEntityRelationsService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class TransportEsTokEntityRelationsAction extends TransportBroadcastAction<
        EsTokEntityRelationRequest,
        EsTokEntityRelationResponse,
        ShardEsTokEntityRelationRequest,
        ShardEsTokEntityRelationResponse> {

    private final ClusterService clusterService;
    private final IndicesService indicesService;
    private final IndexNameExpressionResolver indexNameExpressionResolver;
    private final ProjectResolver projectResolver;
    private final SourceBackedEntityRelationsService relationsService;

    @Inject
    public TransportEsTokEntityRelationsAction(
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
                new SourceBackedEntityRelationsService());
    }

    TransportEsTokEntityRelationsAction(
            ClusterService clusterService,
            TransportService transportService,
            ActionFilters actionFilters,
            ProjectResolver projectResolver,
            IndexNameExpressionResolver indexNameExpressionResolver,
            IndicesService indicesService,
            SourceBackedEntityRelationsService relationsService) {
        super(
                EsTokEntityRelationsAction.NAME,
                clusterService,
                transportService,
                actionFilters,
                indexNameExpressionResolver,
                EsTokEntityRelationRequest::new,
                ShardEsTokEntityRelationRequest::new,
                transportService.getThreadPool().executor(ThreadPool.Names.SEARCH));
        this.clusterService = clusterService;
        this.indicesService = indicesService;
        this.indexNameExpressionResolver = indexNameExpressionResolver;
        this.projectResolver = projectResolver;
        this.relationsService = relationsService;
    }

    @Override
    protected EsTokEntityRelationResponse newResponse(
            EsTokEntityRelationRequest request,
            AtomicReferenceArray<?> shardsResponses,
            ClusterState clusterState) {
        int successfulShards = 0;
        int failedShards = 0;
        List<DefaultShardOperationFailedException> shardFailures = null;
        Map<String, AggregatedVideo> aggregatedVideos = new HashMap<>();
        Map<Long, AggregatedOwner> aggregatedOwners = new HashMap<>();

        for (int index = 0; index < shardsResponses.length(); index++) {
            Object shardResponse = shardsResponses.get(index);
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

            successfulShards++;
            ShardEsTokEntityRelationResponse response = (ShardEsTokEntityRelationResponse) shardResponse;
            for (EsTokRelatedVideoOption video : response.videos()) {
                aggregatedVideos.computeIfAbsent(video.bvid(), ignored -> new AggregatedVideo(video.bvid()))
                        .add(video);
            }
            for (EsTokRelatedOwnerOption owner : response.owners()) {
                aggregatedOwners.computeIfAbsent(owner.mid(), AggregatedOwner::new)
                        .add(owner);
            }
        }

        List<EsTokRelatedVideoOption> videos = aggregatedVideos.values().stream()
                .map(AggregatedVideo::toOption)
                .sorted(Comparator
                        .comparingDouble(EsTokRelatedVideoOption::score).reversed()
                        .thenComparing(Comparator.comparingInt(EsTokRelatedVideoOption::docFreq).reversed())
                        .thenComparing(EsTokRelatedVideoOption::bvid))
                .toList();
        videos = promoteSeedOwnerVideo(request, videos).stream()
            .limit(request.size())
            .toList();
        List<EsTokRelatedOwnerOption> owners = aggregatedOwners.values().stream()
                .map(AggregatedOwner::toOption)
                .sorted(Comparator
                        .comparingDouble(EsTokRelatedOwnerOption::score).reversed()
                        .thenComparing(Comparator.comparingInt(EsTokRelatedOwnerOption::docFreq).reversed())
                        .thenComparing(EsTokRelatedOwnerOption::name))
                .limit(request.size())
                .toList();

        return new EsTokEntityRelationResponse(
                request.relation(),
                request.bvids(),
                request.mids(),
                videos,
                owners,
                shardsResponses.length(),
                successfulShards,
                failedShards,
                shardFailures);
    }

    private static List<EsTokRelatedVideoOption> promoteSeedOwnerVideo(
            EsTokEntityRelationRequest request,
            List<EsTokRelatedVideoOption> videos) {
        if (!EsTokEntityRelationRequest.RELATED_VIDEOS_BY_OWNERS.equals(request.relation())
                || videos.isEmpty()
                || request.mids() == null
                || request.mids().isEmpty()) {
            return videos;
        }

        Set<Long> seedOwnerMids = new HashSet<>(request.mids());
        int sameOwnerIndex = -1;
        for (int index = 0; index < videos.size(); index++) {
            if (seedOwnerMids.contains(videos.get(index).ownerMid())) {
                sameOwnerIndex = index;
                break;
            }
        }
        if (sameOwnerIndex <= 0) {
            return videos;
        }

        List<EsTokRelatedVideoOption> reordered = new ArrayList<>(videos.size());
        reordered.add(videos.get(sameOwnerIndex));
        for (int index = 0; index < videos.size(); index++) {
            if (index == sameOwnerIndex) {
                continue;
            }
            reordered.add(videos.get(index));
        }
        return reordered;
    }

    @Override
    protected ShardEsTokEntityRelationRequest newShardRequest(int numShards, ShardRouting shard, EsTokEntityRelationRequest request) {
        return new ShardEsTokEntityRelationRequest(shard.shardId(), request);
    }

    @Override
    protected ShardEsTokEntityRelationResponse readShardResponse(StreamInput in) throws IOException {
        return new ShardEsTokEntityRelationResponse(in);
    }

    @Override
    protected ShardEsTokEntityRelationResponse shardOperation(ShardEsTokEntityRelationRequest request, Task task) throws IOException {
        IndexService indexService = indicesService.indexServiceSafe(request.shardId().getIndex());
        IndexShard indexShard = indexService.getShard(request.shardId().id());
        try (org.elasticsearch.index.engine.Engine.Searcher searcher = indexShard.acquireSearcher("es_tok_entity_relations")) {
            SourceBackedEntityRelationsService.RelationResult result = relationsService.searchRelations(
                    searcher,
                    indexService,
                    request.relation(),
                    request.bvids(),
                    request.mids(),
                    request.size(),
                    request.scanLimit());
            List<EsTokRelatedVideoOption> videos = result.videos().stream()
                    .map(video -> new EsTokRelatedVideoOption(video.bvid(), video.title(), video.ownerMid(), video.ownerName(), video.docFreq(), video.score(), 1))
                    .toList();
            List<EsTokRelatedOwnerOption> owners = result.owners().stream()
                    .map(owner -> new EsTokRelatedOwnerOption(owner.mid(), owner.name(), owner.docFreq(), owner.score(), 1))
                    .toList();
            return new ShardEsTokEntityRelationResponse(request.shardId(), videos, owners);
        }
    }

    @Override
    protected List<ShardIterator> shards(ClusterState clusterState, EsTokEntityRelationRequest request, String[] concreteIndices) {
        ProjectState projectState = projectResolver.getProjectState(clusterState);
        Map<String, java.util.Set<String>> routingMap = indexNameExpressionResolver.resolveSearchRouting(
                projectState.metadata(),
                null,
                request.indices());
        return clusterService.operationRouting().searchShards(projectState, concreteIndices, routingMap, "_local");
    }

    @Override
    protected ClusterBlockException checkGlobalBlock(ClusterState state, EsTokEntityRelationRequest request) {
        return state.blocks().globalBlockedException(projectResolver.getProjectId(), ClusterBlockLevel.READ);
    }

    @Override
    protected ClusterBlockException checkRequestBlock(ClusterState state, EsTokEntityRelationRequest request, String[] concreteIndices) {
        return state.blocks().indicesBlockedException(projectResolver.getProjectId(), ClusterBlockLevel.READ, concreteIndices);
    }

    private static final class AggregatedVideo {
        private final String bvid;
        private String title = "";
        private long ownerMid = -1L;
        private String ownerName = "";
        private int docFreq;
        private float score;
        private float bestScore;
        private int shardCount;

        private AggregatedVideo(String bvid) {
            this.bvid = bvid;
        }

        private void add(EsTokRelatedVideoOption option) {
            docFreq += option.docFreq();
            score += option.score();
            shardCount += option.shardCount();
            if (option.score() >= bestScore) {
                bestScore = option.score();
                title = option.title();
                ownerMid = option.ownerMid();
                ownerName = option.ownerName();
            }
        }

        private EsTokRelatedVideoOption toOption() {
            return new EsTokRelatedVideoOption(bvid, title, ownerMid, ownerName, docFreq, score, shardCount);
        }
    }

    private static final class AggregatedOwner {
        private final long mid;
        private final Map<String, Float> nameScores = new HashMap<>();
        private int docFreq;
        private float score;
        private int shardCount;

        private AggregatedOwner(long mid) {
            this.mid = mid;
        }

        private void add(EsTokRelatedOwnerOption option) {
            docFreq += option.docFreq();
            score += option.score();
            shardCount += option.shardCount();
            nameScores.merge(option.name(), option.score(), Float::sum);
        }

        private EsTokRelatedOwnerOption toOption() {
            String displayName = nameScores.entrySet().stream()
                    .max(Map.Entry.<String, Float>comparingByValue().thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElse(Long.toString(mid));
            return new EsTokRelatedOwnerOption(mid, displayName, docFreq, score, shardCount);
        }
    }
}