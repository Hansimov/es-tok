package org.es.tok.suggest;

import org.apache.lucene.index.IndexReader;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CachedShardSuggestService {

    private final int maxEntries;
    private final Map<CacheKey, List<LuceneIndexSuggester.SuggestionOption>> cache;

    public CachedShardSuggestService() {
        this(1024);
    }

    public CachedShardSuggestService(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1");
        }
        this.maxEntries = maxEntries;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, List<LuceneIndexSuggester.SuggestionOption>> eldest) {
                return size() > CachedShardSuggestService.this.maxEntries;
            }
        };
    }

    public SuggestResult suggest(
            IndexReader reader,
            String mode,
            List<String> fields,
            String text,
            LuceneIndexSuggester.CompletionConfig config,
            LuceneIndexSuggester.CorrectionConfig correctionConfig,
            boolean cacheEnabled) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(config, "config");

        Object configKey = switch (mode) {
            case "correction" -> correctionConfig;
            case "auto" -> new AutoConfigKey(config, correctionConfig);
            default -> config;
        };
        CacheKey cacheKey = cacheEnabled ? createKey(reader, mode, fields, text, configKey) : null;
        if (cacheKey != null) {
            synchronized (cache) {
                List<LuceneIndexSuggester.SuggestionOption> cached = cache.get(cacheKey);
                if (cached != null) {
                    return new SuggestResult(cached, true);
                }
            }
        }

        LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
        List<LuceneIndexSuggester.SuggestionOption> computed = switch (mode) {
            case "prefix" -> suggester.suggestPrefixCompletions(fields, text, config).stream()
                    .map(candidate -> new LuceneIndexSuggester.SuggestionOption(
                            candidate.text(),
                            candidate.docFreq(),
                            candidate.score(),
                            candidate.type().name().toLowerCase()))
                    .toList();
            case "next_token" -> suggester.suggestNextTokenCompletions(fields, text, config).stream()
                    .map(candidate -> new LuceneIndexSuggester.SuggestionOption(
                            candidate.text(),
                            candidate.docFreq(),
                            candidate.score(),
                            candidate.type().name().toLowerCase()))
                    .toList();
            case "correction" -> suggester.suggestCorrections(fields, text, correctionConfig);
                case "auto" -> suggester.suggestAuto(fields, text, config, correctionConfig);
            default -> throw new IllegalArgumentException("Unsupported suggest mode: " + mode);
        };
        List<LuceneIndexSuggester.SuggestionOption> immutable = List.copyOf(computed);

        if (cacheKey != null) {
            synchronized (cache) {
                cache.put(cacheKey, immutable);
            }
        }

        return new SuggestResult(immutable, false);
    }

    public void prewarmPinyin(IndexReader reader, List<String> fields) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(fields, "fields");
        LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
        suggester.prewarmCompletionIndices(fields);
        suggester.prewarmPinyinIndices(fields);
    }

    private CacheKey createKey(
            IndexReader reader,
            String mode,
            List<String> fields,
            String text,
            Object config) {
        IndexReader.CacheHelper cacheHelper = reader.getReaderCacheHelper();
        if (cacheHelper == null) {
            return null;
        }
        return new CacheKey(cacheHelper.getKey(), mode, List.copyOf(fields), text, config);
    }

    public record SuggestResult(List<LuceneIndexSuggester.SuggestionOption> options, boolean cacheHit) {
    }

    private record CacheKey(
            Object readerKey,
            String mode,
            List<String> fields,
            String text,
            Object config) {
    }

    private record AutoConfigKey(
            LuceneIndexSuggester.CompletionConfig completionConfig,
            LuceneIndexSuggester.CorrectionConfig correctionConfig) {
    }
}