package org.es.tok;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.es.tok.analysis.EsTokAnalyzer;
import org.es.tok.suggest.LuceneIndexSuggester;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SuggestionEngineTest {

    @Test
    public void testCorrectionUsesPopularNearbyTerm() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new StandardAnalyzer()) {
            buildIndex(directory, analyzer,
                    "github copilot",
                    "github copilot",
                    "github actions",
                    "gitlab runner");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                LuceneIndexSuggester.Correction correction = suggester.suggestCorrection(
                        List.of("content"),
                        "gihtub",
                        LuceneIndexSuggester.CorrectionConfig.defaults());

                assertTrue(correction.changed());
                assertEquals("github", correction.suggested());
                assertTrue(correction.suggestedDocFreq() > correction.originalDocFreq());
            }
        }
    }

    @Test
    public void testCorrectionSkipsCommonTerm() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new StandardAnalyzer()) {
            buildIndex(directory, analyzer,
                    "github copilot",
                    "github actions",
                    "github enterprise");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                LuceneIndexSuggester.Correction correction = suggester.suggestCorrection(
                        List.of("content"),
                        "github",
                        LuceneIndexSuggester.CorrectionConfig.defaults());

                assertFalse(correction.changed());
                assertEquals("github", correction.suggested());
            }
        }
    }

    @Test
    public void testPhraseCorrectionBuildsCorrectedTextOption() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new StandardAnalyzer()) {
            buildIndex(directory, analyzer,
                    "github copilot",
                    "github copilot chat",
                    "github actions");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.SuggestionOption> corrections = suggester.suggestCorrections(
                        List.of("content"),
                        "gihtub copolit",
                        LuceneIndexSuggester.CorrectionConfig.defaults());

                assertFalse(corrections.isEmpty());
                assertEquals("github copilot", corrections.get(0).text());
                assertEquals("correction", corrections.get(0).type());
            }
        }
    }

    @Test
    public void testCorrectionReturnsTopNCandidates() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new StandardAnalyzer()) {
            buildIndex(directory, analyzer,
                    "color",
                    "color",
                    "colon",
                    "colony");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.SuggestionOption> corrections = suggester.suggestCorrections(
                        List.of("content"),
                        "colo",
                        new LuceneIndexSuggester.CorrectionConfig(0, 4, 2, 1, 2, 1, 0.5f));

                assertEquals(2, corrections.size());
                assertEquals("color", corrections.get(0).text());
                assertEquals("colon", corrections.get(1).text());
                assertTrue(corrections.get(0).score() >= corrections.get(1).score());
            }
        }
    }

    @Test
    public void testPrefixCompletionReturnsHighestFrequencyTerms() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new StandardAnalyzer()) {
            buildIndex(directory, analyzer,
                    "github copilot",
                    "github actions",
                    "gitlab runner",
                    "gitea forge");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestPrefixCompletions(
                        List.of("content"),
                        "git",
                        new LuceneIndexSuggester.CompletionConfig(3, 32, 1, 3, true));

                assertFalse(completions.isEmpty());
                assertTrue(completions.stream().anyMatch(candidate -> candidate.text().equals("github")));
                assertTrue(completions.stream().anyMatch(candidate -> candidate.text().equals("gitlab")));
            }
        }
    }

    @Test
    public void testNextTokenCompletionUsesEsTokBigrams() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new EsTokAnalyzer(
                        ConfigBuilder.create()
                                .withCategSplitWord()
                                .withBigram()
                                .withIgnoreCase()
                                .withDropDuplicates()
                                .build())) {
            buildIndex(directory, analyzer,
                    "github copilot",
                    "github copilot chat",
                    "github actions");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestNextTokenCompletions(
                        List.of("content"),
                        "github",
                        new LuceneIndexSuggester.CompletionConfig(3, 32, 1, 1, true));

                assertFalse(completions.isEmpty());
                assertEquals("copilot", completions.get(0).text());
                assertEquals(LuceneIndexSuggester.CompletionType.NEXT_TOKEN, completions.get(0).type());
                assertTrue(completions.stream().anyMatch(candidate -> candidate.text().equals("actions")));
            }
        }
    }

    @Test
    public void testCompactBigramCompletionWorksForChineseTokens() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new EsTokAnalyzer(
                        ConfigBuilder.create()
                                .withVocab("深度", "学习", "模型")
                                .withCategSplitWord()
                                .withVcgram()
                                .withIgnoreCase()
                                .withDropDuplicates()
                                .build())) {
            buildIndex(directory, analyzer,
                    "深度学习模型",
                    "深度学习",
                    "学习模型");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestNextTokenCompletions(
                        List.of("content"),
                        "深度",
                        new LuceneIndexSuggester.CompletionConfig(3, 32, 1, 1, true));

                assertFalse(completions.isEmpty());
                assertTrue(completions.stream().anyMatch(candidate -> candidate.text().equals("学习")));
            }
        }
    }

    private void buildIndex(Directory directory, Analyzer analyzer, String... contents) throws Exception {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (String content : contents) {
                Document document = new Document();
                document.add(new TextField("content", content, TextField.Store.NO));
                writer.addDocument(document);
            }
            writer.commit();
        }
    }
}