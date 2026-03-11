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
import org.elasticsearch.threadpool.ThreadPool;

import java.io.Closeable;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PinyinWarmupIndexListener implements IndexEventListener, Closeable {

    private static final Logger LOGGER = LogManager.getLogger(PinyinWarmupIndexListener.class);

    private final Map<String, List<String>> warmupFieldsByIndex = new ConcurrentHashMap<>();

    @Override
    public void afterIndexCreated(IndexService indexService) {
        List<String> warmupFields = discoverWarmupFields(indexService);
        String indexName = indexName(indexService.getIndexSettings());
        if (warmupFields.isEmpty()) {
            warmupFieldsByIndex.remove(indexName);
            return;
        }
        warmupFieldsByIndex.put(indexName, warmupFields);
    }

    @Override
    public void afterIndexRemoved(org.elasticsearch.index.Index index, IndexSettings indexSettings, IndexRemovalReason reason) {
        warmupFieldsByIndex.remove(indexName(indexSettings));
    }

    @Override
    public void afterIndexShardStarted(IndexShard indexShard) {
        List<String> warmupFields = warmupFieldsByIndex.computeIfAbsent(
                indexShard.shardId().getIndexName(),
                ignored -> discoverWarmupFields(indexShard.mapperService()));
        if (warmupFields == null || warmupFields.isEmpty()) {
            return;
        }
        indexShard.getThreadPool().executor(ThreadPool.Names.GENERIC).execute(() -> prewarmShard(indexShard, warmupFields));
    }

    @Override
    public void close() throws IOException {
    }

    static List<String> discoverWarmupFields(IndexService indexService) {
        return discoverWarmupFields(indexService.mapperService());
    }

    static List<String> discoverWarmupFields(MapperService mapperService) {
        if (mapperService == null) {
            return List.of();
        }
        MappingLookup mappingLookup = mapperService.mappingLookup();
        Set<String> mappedFields = mappingLookup.getFullNameToFieldType().keySet();
        return mappedFields
                .stream()
            .filter(field -> shouldPrewarmField(field, mappedFields))
                .sorted(Comparator
                        .comparingInt((String field) -> field.endsWith(".suggest") ? 0 : 1)
                        .thenComparing(String::compareTo))
            .limit(8)
                .toList();
    }

    private void prewarmShard(IndexShard indexShard, List<String> warmupFields) {
        try (Engine.Searcher searcher = indexShard.acquireSearcher("es_tok_pinyin_warmup")) {
            IndexReader reader = searcher.getIndexReader();
            new LuceneIndexSuggester(reader).prewarmPinyinIndices(warmupFields);
        } catch (Exception exception) {
            LOGGER.debug(
                    "failed to prewarm es_tok pinyin cache for shard {} and fields {}",
                    indexShard.shardId(),
                    warmupFields,
                    exception);
        }
    }

    private static boolean shouldPrewarmField(String field, Set<String> mappedFields) {
        if (field.endsWith(".suggest")) {
            return true;
        }
        if (!field.endsWith(".words")) {
            return false;
        }
        String suggestField = field.substring(0, field.length() - ".words".length()) + ".suggest";
        return mappedFields.contains(suggestField) == false;
    }

    private static String indexName(IndexSettings indexSettings) {
        return indexSettings.getIndex().getName();
    }
}