package org.es.tok;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.es.tok.suggest.LuceneIndexSuggester.SuggestionOption;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.es.tok.suggest.CachedShardSuggestService;
import org.es.tok.suggest.LuceneIndexSuggester;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SuggestionPerformanceTest {

    public static void main(String[] args) throws IOException {
        System.out.println("=== ES-TOK Suggestion Performance Tests ===\n");

        try (Directory directory = new ByteBuffersDirectory()) {
            indexSuggestionCorpus(directory);
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                CachedShardSuggestService cachedService = new CachedShardSuggestService(2048);
                LuceneIndexSuggester.CompletionConfig prefixConfig = new LuceneIndexSuggester.CompletionConfig(5, 64, 1, 1, true);

                printProbeResult("Prefix probe: git", cachedService.suggest(
                    reader,
                    "prefix",
                    List.of("content"),
                    "git",
                    prefixConfig,
                    LuceneIndexSuggester.CorrectionConfig.defaults(),
                    false).options());
                printProbeResult("Next-token probe: github", cachedService.suggest(
                    reader,
                    "next_token",
                    List.of("content"),
                    "github",
                    prefixConfig,
                    LuceneIndexSuggester.CorrectionConfig.defaults(),
                    false).options());
                printProbeResult("Correction probe: gihtub copolit", cachedService.suggest(
                    reader,
                    "correction",
                    List.of("content"),
                    "gihtub copolit",
                    prefixConfig,
                    LuceneIndexSuggester.CorrectionConfig.defaults(),
                    false).options());

                benchmark("Prefix Completion (cold)", 5000, () -> cachedService.suggest(
                        reader,
                        "prefix",
                        List.of("content"),
                        "git",
                        prefixConfig,
                    LuceneIndexSuggester.CorrectionConfig.defaults(),
                        false));

                benchmark("Prefix Completion (cached)", 5000, () -> cachedService.suggest(
                        reader,
                        "prefix",
                        List.of("content"),
                        "git",
                        prefixConfig,
                    LuceneIndexSuggester.CorrectionConfig.defaults(),
                        true));

                benchmark("Next Token Completion (cached)", 5000, () -> cachedService.suggest(
                        reader,
                        "next_token",
                        List.of("content"),
                        "github",
                        prefixConfig,
                    LuceneIndexSuggester.CorrectionConfig.defaults(),
                        true));

                benchmark("Correction (cached)", 5000, () -> cachedService.suggest(
                    reader,
                    "correction",
                    List.of("content"),
                    "gihtub copolit",
                    prefixConfig,
                    LuceneIndexSuggester.CorrectionConfig.defaults(),
                    true));
            }
        } catch (Exception e) {
            throw new IOException("Suggestion performance benchmark failed", e);
        }
    }

    private static void benchmark(String name, int iterations, ThrowingRunnable runnable) throws Exception {
        for (int i = 0; i < 100; i++) {
            runnable.run();
        }

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            runnable.run();
        }
        long end = System.nanoTime();

        double totalMs = (end - start) / 1_000_000.0;
        System.out.printf("%s: total=%.2f ms avg=%.4f ms throughput=%.2f ops/sec%n",
                name,
                totalMs,
                totalMs / iterations,
                iterations / (totalMs / 1000.0));
    }

    private static void printProbeResult(String title, List<SuggestionOption> options) {
        System.out.println(title + ":");
        if (options.isEmpty()) {
            System.out.println("  <no options>");
            return;
        }
        for (SuggestionOption option : options) {
            System.out.printf("  %s | freq=%d | score=%.4f | type=%s%n",
                    option.text(),
                    option.docFreq(),
                    option.score(),
                    option.type());
        }
    }

    private static void indexSuggestionCorpus(Directory directory) throws Exception {
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
            for (String content : buildCorpus()) {
                Document document = new Document();
                document.add(new TextField("content", content, TextField.Store.NO));
                writer.addDocument(document);
            }
            writer.commit();
        }
    }

    private static List<String> buildCorpus() {
        List<String> corpus = new ArrayList<>();
        corpus.add("github copilot");
        corpus.add("github copilot chat");
        corpus.add("github actions");
        corpus.add("gitlab runner");
        corpus.add("gitea forge");
        corpus.add("gitops workflow");
        for (int i = 0; i < 2000; i++) {
            corpus.add("github repository " + i);
            corpus.add("github issue " + i);
            corpus.add("gitlab pipeline " + i);
        }
        return corpus;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}