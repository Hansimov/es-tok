package org.es.tok.suggest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.IndexReader;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.shard.IndexEventListener;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.cluster.IndexRemovalReason;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class PinyinWarmupIndexListener implements IndexEventListener, Closeable {

    private static final int WARMUP_PENDING = 0;
    private static final int WARMUP_RUNNING = 1;
    private static final int WARMUP_COMPLETE = 2;

    private static final Logger LOGGER = LogManager.getLogger(PinyinWarmupIndexListener.class);
    private static final List<String> PREFERRED_WARMUP_FIELDS = List.of(
            "owner.name.suggest",
            "title.assoc",
            "title.suggest",
            "tags.assoc",
            "tags.suggest",
            "title.words",
            "tags.words");

    private final Map<String, List<String>> warmupFieldsByIndex = new ConcurrentHashMap<>();
    private final Set<String> queuedWarmups = ConcurrentHashMap.newKeySet();
    private final Map<String, WarmupState> warmupStates = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Executor warmupExecutor = Runnable::run;

    public void configureExecutor(Executor warmupExecutor) {
        this.warmupExecutor = Objects.requireNonNull(warmupExecutor, "warmupExecutor");
    }

    @Override
    public void afterIndexCreated(IndexService indexService) {
        String indexName = indexName(indexService.getIndexSettings());
        if (shouldWarmIndex(indexName) == false) {
            warmupFieldsByIndex.remove(indexName);
            return;
        }
        List<String> warmupFields = discoverWarmupFields(indexService);
        if (warmupFields.isEmpty()) {
            warmupFieldsByIndex.remove(indexName);
            return;
        }
        warmupFieldsByIndex.put(indexName, warmupFields);
    }

    @Override
    public void afterIndexRemoved(org.elasticsearch.index.Index index, IndexSettings indexSettings, IndexRemovalReason reason) {
        String removedIndexName = indexName(indexSettings);
        warmupFieldsByIndex.remove(removedIndexName);
        queuedWarmups.removeIf(shardKey -> shardKey.startsWith(removedIndexName + "["));
        warmupStates.keySet().removeIf(shardKey -> shardKey.startsWith(removedIndexName + "["));
    }

    @Override
    public void afterIndexShardStarted(IndexShard indexShard) {
        if (closed.get()) {
            return;
        }
        String indexName = indexShard.shardId().getIndexName();
        if (shouldWarmIndex(indexName) == false) {
            return;
        }
        List<String> warmupFields = warmupFieldsByIndex.computeIfAbsent(
                indexName,
                ignored -> discoverWarmupFields(indexName, indexShard.mapperService()));
        if (warmupFields == null || warmupFields.isEmpty()) {
            return;
        }

        String shardKey = indexShard.shardId().toString();
        WarmupState warmupState = warmupStates.computeIfAbsent(shardKey, ignored -> new WarmupState());
        if (warmupState.isComplete() || !queuedWarmups.add(shardKey)) {
            return;
        }

        try {
            warmupExecutor.execute(() -> runAsyncWarmup(indexShard, warmupFields, shardKey, warmupState));
        } catch (RuntimeException exception) {
            queuedWarmups.remove(shardKey);
            warmupStates.remove(shardKey, warmupState);
            if (!closed.get()) {
                LOGGER.debug("failed to queue es_tok warmup for shard {} and fields {}", indexShard.shardId(), warmupFields, exception);
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        queuedWarmups.clear();
        warmupStates.clear();
    }

    static List<String> discoverWarmupFields(IndexService indexService) {
        return discoverWarmupFields(indexName(indexService.getIndexSettings()), indexService.mapperService());
    }

    static List<String> discoverWarmupFields(MapperService mapperService) {
        return discoverWarmupFields(null, mapperService);
    }

    static List<String> discoverWarmupFields(String indexName, MapperService mapperService) {
        if (mapperService == null) {
            return List.of();
        }
        if (shouldWarmIndex(indexName) == false) {
            return List.of();
        }
        MappingLookup mappingLookup = mapperService.mappingLookup();
        return discoverWarmupFields(indexName, mappingLookup.getFullNameToFieldType().keySet());
    }

    static List<String> discoverWarmupFields(String indexName, Set<String> mappedFields) {
        if (mappedFields == null || mappedFields.isEmpty() || shouldWarmIndex(indexName) == false) {
            return List.of();
        }
        List<String> preferred = PREFERRED_WARMUP_FIELDS.stream()
                .filter(field -> shouldPrewarmField(field, mappedFields))
                .toList();
        if (preferred.isEmpty() == false) {
            return preferred;
        }
        return mappedFields
                .stream()
            .filter(field -> shouldPrewarmField(field, mappedFields))
                .sorted(Comparator
                        .comparingInt((String field) -> field.endsWith(".suggest") ? 0 : 1)
                        .thenComparing(String::compareTo))
            .limit(8)
                .toList();
    }

    public WarmupSummary businessWarmupSummary() {
        int totalShards = 0;
        int readyShards = 0;
        int runningShards = 0;
        int queuedShards = 0;
        for (Map.Entry<String, WarmupState> entry : warmupStates.entrySet()) {
            if (isSystemShard(entry.getKey())) {
                continue;
            }
            totalShards++;
            int phase = entry.getValue().phase();
            if (phase == WARMUP_COMPLETE) {
                readyShards++;
            } else if (phase == WARMUP_RUNNING) {
                runningShards++;
            } else {
                queuedShards++;
            }
        }
        return new WarmupSummary(totalShards, readyShards, runningShards, queuedShards);
    }

    private void runAsyncWarmup(
            IndexShard indexShard,
            List<String> warmupFields,
            String shardKey,
            WarmupState warmupState) {
        try {
            if (!warmupState.tryStart()) {
                return;
            }
            warmShard(indexShard, warmupFields, shardKey, warmupState, "startup_async");
        } catch (IOException exception) {
            if (!closed.get()) {
                LOGGER.debug(
                        "failed to prewarm es_tok pinyin cache for shard {} and fields {}",
                        indexShard.shardId(),
                        warmupFields,
                        exception);
            }
        } finally {
            queuedWarmups.remove(shardKey);
        }
    }

    private void warmShard(IndexShard indexShard, List<String> warmupFields, String shardKey, WarmupState warmupState, String trigger)
            throws IOException {
        try (Engine.Searcher searcher = indexShard.acquireSearcher("es_tok_pinyin_warmup")) {
            IndexReader reader = searcher.getIndexReader();
            List<String> completionFields = completionWarmupFields(warmupFields);
            List<String> pinyinFields = pinyinWarmupFields(warmupFields);
            CachedShardSuggestService suggestService = new CachedShardSuggestService();
            long startedAt = System.nanoTime();
            suggestService.prewarmFields(reader, completionFields, pinyinFields);
            long completionFinishedAt = System.nanoTime();
            long finishedAt = System.nanoTime();
            LOGGER.info(
                "es_tok warmup shard={} trigger={} completion_fields={} pinyin_fields={} completion_ms={} pinyin_ms={} total_ms={}",
                    indexShard.shardId(),
                    trigger,
                completionFields,
                pinyinFields,
                    nanosToMillis(completionFinishedAt - startedAt),
                    nanosToMillis(finishedAt - completionFinishedAt),
                    nanosToMillis(finishedAt - startedAt));
            warmupState.complete();
        } catch (IOException exception) {
            queuedWarmups.remove(shardKey);
            warmupStates.remove(shardKey, warmupState);
            warmupState.fail(exception);
            throw exception;
        } catch (RuntimeException exception) {
            queuedWarmups.remove(shardKey);
            warmupStates.remove(shardKey, warmupState);
            warmupState.fail(exception);
            throw exception;
        }
    }

    private static boolean shouldPrewarmField(String field, Set<String> mappedFields) {
        if (mappedFields.contains(field) == false) {
            return false;
        }
        if (field.endsWith(".assoc")) {
            return true;
        }
        if (field.endsWith(".suggest")) {
            return true;
        }
        if (!field.endsWith(".words")) {
            return false;
        }
        String suggestField = field.substring(0, field.length() - ".words".length()) + ".suggest";
        return mappedFields.contains(suggestField) == false;
    }

    private static List<String> completionWarmupFields(List<String> warmupFields) {
        return warmupFields == null ? List.of() : warmupFields;
    }

    private static List<String> pinyinWarmupFields(List<String> warmupFields) {
        if (warmupFields == null || warmupFields.isEmpty()) {
            return List.of();
        }
        return warmupFields.stream()
                .filter(field -> field.endsWith(".assoc") == false)
                .toList();
    }

    private static String indexName(IndexSettings indexSettings) {
        return indexSettings.getIndex().getName();
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static boolean isSystemShard(String shardKey) {
        return shardKey.startsWith("[.");
    }

    static boolean shouldWarmIndex(String indexName) {
        return indexName != null && indexName.startsWith(".") == false;
    }

    public record WarmupSummary(int totalShards, int readyShards, int runningShards, int queuedShards) {
        public boolean isReady() {
            return totalShards == 0 || readyShards >= totalShards;
        }

        public int activeShards() {
            return runningShards + queuedShards;
        }
    }

    private static final class WarmupState {
        private final AtomicInteger phase = new AtomicInteger(WARMUP_PENDING);
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private boolean isComplete() {
            return phase.get() == WARMUP_COMPLETE;
        }

        private boolean tryStart() {
            return phase.compareAndSet(WARMUP_PENDING, WARMUP_RUNNING);
        }

        private void complete() {
            phase.set(WARMUP_COMPLETE);
            completion.complete(null);
        }

        private void fail(Throwable throwable) {
            phase.set(WARMUP_PENDING);
            completion.completeExceptionally(throwable);
        }

        private int phase() {
            return phase.get();
        }
    }
}