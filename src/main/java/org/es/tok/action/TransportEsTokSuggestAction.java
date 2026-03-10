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
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.es.tok.suggest.CachedShardSuggestService;
import org.es.tok.suggest.LuceneIndexSuggester;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class TransportEsTokSuggestAction extends TransportBroadcastAction<
        EsTokSuggestRequest,
        EsTokSuggestResponse,
        ShardEsTokSuggestRequest,
        ShardEsTokSuggestResponse> {

    private final IndicesService indicesService;
    private final ProjectResolver projectResolver;
    private final CachedShardSuggestService suggestService;

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
                new CachedShardSuggestService());
    }

    TransportEsTokSuggestAction(
            ClusterService clusterService,
            TransportService transportService,
            ActionFilters actionFilters,
            ProjectResolver projectResolver,
            IndexNameExpressionResolver indexNameExpressionResolver,
            IndicesService indicesService,
            CachedShardSuggestService suggestService) {
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
                        ignored -> new AggregatedOption(option.text(), option.type()));
                aggregated.add(option.docFreq(), option.score(), option.shardCount());
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
        try (Engine.Searcher searcher = indexShard.acquireSearcher("es_tok_suggest")) {
            IndexReader reader = searcher.getIndexReader();
            CachedShardSuggestService.SuggestResult result = suggestService.suggest(
                    reader,
                    request.mode(),
                    request.fields(),
                    request.text(),
                    new LuceneIndexSuggester.CompletionConfig(
                            request.size(),
                            request.scanLimit(),
                            request.minPrefixLength(),
                            request.minCandidateLength(),
                            request.allowCompactBigrams()),
                            new LuceneIndexSuggester.CorrectionConfig(
                                request.correctionRareDocFreq(),
                                request.correctionMinLength(),
                                request.correctionMaxEdits(),
                                request.correctionPrefixLength(),
                                request.size(),
                                1,
                                0.5f),
                    request.useCache());

            List<EsTokSuggestOption> options = result.options().stream()
                            .map(candidate -> new EsTokSuggestOption(candidate.text(), candidate.docFreq(), candidate.score(), candidate.type(), 1))
                    .toList();
            return new ShardEsTokSuggestResponse(request.shardId(), options, result.cacheHit());
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
        private final String type;
        private int docFreq;
        private float score;
        private int shardCount;

        private AggregatedOption(String text, String type) {
            this.text = text;
            this.type = type;
        }

        private void add(int docFreq, float score, int shardCount) {
            this.docFreq += docFreq;
            this.score += score;
            this.shardCount += shardCount;
        }

        private EsTokSuggestOption toOption() {
            return new EsTokSuggestOption(text, docFreq, score, type, shardCount);
        }
    }
}