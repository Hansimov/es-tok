package org.es.tok;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.es.tok.suggest.CachedShardSuggestService;
import org.es.tok.suggest.LuceneIndexSuggester;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SuggestionCacheTest {

    @Test
    public void testSecondLookupHitsCache() throws Exception {
        try (Directory directory = new ByteBuffersDirectory()) {
            writeDocuments(directory, "github copilot", "github actions", "gitlab runner");
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                CachedShardSuggestService service = new CachedShardSuggestService(8);
                LuceneIndexSuggester.CompletionConfig config = new LuceneIndexSuggester.CompletionConfig(5, 32, 1, 1, true);

                CachedShardSuggestService.SuggestResult first = service.suggest(
                        reader,
                        "prefix",
                        List.of("content"),
                        "git",
                        config,
                    LuceneIndexSuggester.CorrectionConfig.defaults(),
                        true);
                CachedShardSuggestService.SuggestResult second = service.suggest(
                        reader,
                        "prefix",
                        List.of("content"),
                        "git",
                        config,
                    LuceneIndexSuggester.CorrectionConfig.defaults(),
                        true);

                assertFalse(first.cacheHit());
                assertTrue(second.cacheHit());
                assertFalse(second.options().isEmpty());
            }
        }
    }

    @Test
    public void testReaderChangeInvalidatesCacheKey() throws Exception {
        try (Directory directory = new ByteBuffersDirectory()) {
            writeDocuments(directory, "github copilot", "github actions");
            CachedShardSuggestService service = new CachedShardSuggestService(8);
            LuceneIndexSuggester.CompletionConfig config = new LuceneIndexSuggester.CompletionConfig(5, 32, 1, 1, true);

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                CachedShardSuggestService.SuggestResult first = service.suggest(
                        reader,
                        "prefix",
                        List.of("content"),
                        "git",
                        config,
                    LuceneIndexSuggester.CorrectionConfig.defaults(),
                        true);
                assertFalse(first.cacheHit());
            }

            writeDocuments(directory, "gitea forge");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                CachedShardSuggestService.SuggestResult second = service.suggest(
                        reader,
                        "prefix",
                        List.of("content"),
                        "git",
                        config,
                        LuceneIndexSuggester.CorrectionConfig.defaults(),
                        true);
                assertFalse(second.cacheHit());
            }
        }
    }

    @Test
    public void testCorrectionModeCanHitCache() throws Exception {
        try (Directory directory = new ByteBuffersDirectory()) {
            writeDocuments(directory, "github copilot", "github actions");
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                CachedShardSuggestService service = new CachedShardSuggestService(8);
                CachedShardSuggestService.SuggestResult first = service.suggest(
                        reader,
                        "correction",
                        List.of("content"),
                        "gihtub",
                        LuceneIndexSuggester.CompletionConfig.defaults(),
                        LuceneIndexSuggester.CorrectionConfig.defaults(),
                        true);
                CachedShardSuggestService.SuggestResult second = service.suggest(
                        reader,
                        "correction",
                        List.of("content"),
                        "gihtub",
                        LuceneIndexSuggester.CompletionConfig.defaults(),
                        LuceneIndexSuggester.CorrectionConfig.defaults(),
                        true);

                assertFalse(first.cacheHit());
                assertTrue(second.cacheHit());
                assertFalse(second.options().isEmpty());
            }
        }
    }

    @Test
    public void testAutoModeCanHitCache() throws Exception {
        try (Directory directory = new ByteBuffersDirectory()) {
            writeDocuments(directory, "github copilot", "github actions", "github color");
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                CachedShardSuggestService service = new CachedShardSuggestService(8);
                CachedShardSuggestService.SuggestResult first = service.suggest(
                        reader,
                        "auto",
                        List.of("content"),
                        "github",
                        LuceneIndexSuggester.CompletionConfig.defaults(),
                        LuceneIndexSuggester.CorrectionConfig.defaults(),
                        true);
                CachedShardSuggestService.SuggestResult second = service.suggest(
                        reader,
                        "auto",
                        List.of("content"),
                        "github",
                        LuceneIndexSuggester.CompletionConfig.defaults(),
                        LuceneIndexSuggester.CorrectionConfig.defaults(),
                        true);

                assertFalse(first.cacheHit());
                assertTrue(second.cacheHit());
                assertFalse(second.options().isEmpty());
            }
        }
    }

    private void writeDocuments(Directory directory, String... contents) throws Exception {
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
            for (String content : contents) {
                Document document = new Document();
                document.add(new TextField("content", content, TextField.Store.NO));
                writer.addDocument(document);
            }
            writer.commit();
        }
    }
}