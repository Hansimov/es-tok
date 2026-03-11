package org.es.tok;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
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
import org.es.tok.suggest.PinyinSupport;
import org.junit.Test;

import java.io.StringReader;
import java.util.ArrayList;
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
    public void testPrefixPrefersExpansionOverExactSingleCharacter() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "母",
                    "母",
                    "母",
                    "母亲",
                    "母亲",
                    "母亲",
                    "母亲",
                    "母婴",
                    "母婴",
                    "母婴");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestPrefixCompletions(
                        List.of("content"),
                        "母",
                        new LuceneIndexSuggester.CompletionConfig(3, 32, 1, 1, true));

                assertFalse(completions.isEmpty());
                assertEquals("母亲", completions.get(0).text());
                assertTrue(completions.stream().anyMatch(candidate -> candidate.text().equals("母")));
            }
        }
    }

    @Test
    public void testPrefixNormalizesChineseWhitespaceVariants() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "三 丽",
                    "三丽 鸥",
                    "三丽鸥");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestPrefixCompletions(
                        List.of("content"),
                        "三",
                        new LuceneIndexSuggester.CompletionConfig(3, 32, 1, 1, true));

                assertTrue(completions.stream().anyMatch(candidate -> candidate.text().equals("三丽")));
                assertTrue(completions.stream().anyMatch(candidate -> candidate.text().equals("三丽鸥")));
                assertFalse(completions.stream().anyMatch(candidate -> candidate.text().contains(" ")));
            }
        }
    }

    @Test
    public void testPinyinPrefixSupportsInitials() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "影视飓风",
                    "影视飓风",
                    "影视飓风",
                    "影视前线");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestPrefixCompletions(
                        List.of("content"),
                        "ysjf",
                        new LuceneIndexSuggester.CompletionConfig(3, 32, 1, 1, true, true));

                assertFalse(completions.isEmpty());
                assertTrue(completions.toString(), completions.stream().anyMatch(candidate -> candidate.text().equals("影视飓风")));
            }
        }
    }

    @Test
    public void testPinyinPrefixSupportsMixedFullAndInitialInput() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "影视飓风",
                    "影视飓风",
                    "影视飓风",
                    "影视风云");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestPrefixCompletions(
                        List.of("content"),
                        "yingshjf",
                        new LuceneIndexSuggester.CompletionConfig(3, 32, 1, 1, true, true));

                assertFalse(completions.isEmpty());
                assertTrue(completions.toString(), completions.stream().anyMatch(candidate -> candidate.text().equals("影视飓风")));
            }
        }
    }

    @Test
    public void testSuggestAnalyzerEmitsPrecomputedPinyinTerms() throws Exception {
        try (Analyzer analyzer = new EsTokAnalyzer(
                ConfigBuilder.create()
                        .withVocab("影视飓风")
                        .withIgnoreCase()
                        .withDropDuplicates()
                        .withEmitPinyinTerms()
                        .build());
                TokenStream stream = analyzer.tokenStream("content", new StringReader("影视飓风"))) {
            CharTermAttribute termAttribute = stream.addAttribute(CharTermAttribute.class);
            List<String> terms = new ArrayList<>();
            stream.reset();
            while (stream.incrementToken()) {
                terms.add(termAttribute.toString());
            }
            stream.end();

            assertTrue(terms.contains("影视飓风"));
            assertTrue(terms.contains("__pyf__yins|影视飓风"));
            assertTrue(terms.contains("__pyi__ysjf|影视飓风"));
        }
    }

    @Test
    public void testPrecomputedPinyinPrefixWorksWithoutRuntimeChineseTerms() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new WhitespaceAnalyzer()) {
            buildIndex(directory, analyzer,
                    String.join(" ", PinyinSupport.precomputedSuggestionTerms("影视飓风")),
                    String.join(" ", PinyinSupport.precomputedSuggestionTerms("影视飓风")),
                    String.join(" ", PinyinSupport.precomputedSuggestionTerms("影视剧风")));

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestPrefixCompletions(
                        List.of("content"),
                        "yinsjf",
                        new LuceneIndexSuggester.CompletionConfig(3, 32, 1, 1, true, true));

                assertFalse(completions.isEmpty());
                assertEquals("影视飓风", completions.get(0).text());
            }
        }
    }

    @Test
    public void testAsciiExactPrefixBeatsPinyinInitialsAlias() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new WhitespaceAnalyzer()) {
            List<String> chineseTerms = new ArrayList<>();
            chineseTerms.add("给他爱5");
            chineseTerms.addAll(PinyinSupport.precomputedSuggestionTerms("给他爱5"));

            for (int index = 0; index < 44; index++) {
                buildIndex(directory, analyzer, "gta5");
            }
            for (int index = 0; index < 7; index++) {
                buildIndex(directory, analyzer, String.join(" ", chineseTerms));
            }

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestPrefixCompletions(
                        List.of("content"),
                        "gta5",
                        new LuceneIndexSuggester.CompletionConfig(5, 64, 1, 1, true, true));

                assertFalse(completions.isEmpty());
                assertEquals("gta5", completions.get(0).text());
            }
        }
    }

    @Test
    public void testShortAsciiExactPrefixBeatsChineseHomophoneNoise() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new WhitespaceAnalyzer()) {
            List<String> chineseTerms = new ArrayList<>();
            chineseTerms.add("怪谈");
            chineseTerms.addAll(PinyinSupport.precomputedSuggestionTerms("怪谈"));

            for (int index = 0; index < 360; index++) {
                buildIndex(directory, analyzer, "gta");
            }
            for (int index = 0; index < 275; index++) {
                buildIndex(directory, analyzer, String.join(" ", chineseTerms));
            }

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestPrefixCompletions(
                        List.of("content"),
                        "gta",
                        new LuceneIndexSuggester.CompletionConfig(5, 64, 1, 1, true, true));

                assertFalse(completions.isEmpty());
                assertEquals("gta", completions.get(0).text());
            }
        }
    }

    @Test
    public void testAsciiBrandPrefixBeatsChinesePinyinAliasNoise() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new WhitespaceAnalyzer()) {
            List<String> chineseTerms = new ArrayList<>();
            chineseTerms.add("小妙");
            chineseTerms.addAll(PinyinSupport.precomputedSuggestionTerms("小妙"));

            for (int index = 0; index < 105; index++) {
                buildIndex(directory, analyzer, "xiaomi");
            }
            for (int index = 0; index < 604; index++) {
                buildIndex(directory, analyzer, String.join(" ", chineseTerms));
            }

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestPrefixCompletions(
                        List.of("content"),
                        "xiaomi",
                        new LuceneIndexSuggester.CompletionConfig(5, 64, 1, 1, true, true));

                assertFalse(completions.isEmpty());
                assertEquals("xiaomi", completions.get(0).text());
            }
        }
    }

    @Test
    public void testCrowdedPrecomputedPinyinBucketStillRecallsHighFrequencyChineseAlias() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new WhitespaceAnalyzer()) {
            for (int index = 0; index < 105; index++) {
                buildIndex(directory, analyzer, "xiaomi");
            }

            List<String> xiaomiTerms = new ArrayList<>();
            xiaomiTerms.add("小米");
            xiaomiTerms.addAll(PinyinSupport.precomputedSuggestionTerms("小米"));
            for (int index = 0; index < 420; index++) {
                buildIndex(directory, analyzer, String.join(" ", xiaomiTerms));
            }

            int crowdedTerms = 0;
            for (int codePoint = 0x4E00; codePoint < 0x7C73 && crowdedTerms < 320; codePoint++) {
                if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) {
                    continue;
                }
                String candidate = "小" + new String(Character.toChars(codePoint));
                if (candidate.equals("小米")) {
                    continue;
                }

                List<String> noisyTerms = new ArrayList<>();
                noisyTerms.add(candidate);
                noisyTerms.addAll(PinyinSupport.precomputedSuggestionTerms(candidate));
                buildIndex(directory, analyzer, String.join(" ", noisyTerms));
                crowdedTerms++;
            }

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestPrefixCompletions(
                        List.of("content"),
                        "xiaomi",
                        new LuceneIndexSuggester.CompletionConfig(10, 1, 1, 1, true, true));

                assertFalse(completions.isEmpty());
                assertEquals("xiaomi", completions.get(0).text());
                assertTrue(completions.toString(), completions.stream().anyMatch(candidate -> candidate.text().equals("小米")));
            }
        }
    }

    @Test
    public void testPrecomputedPinyinCorrectionWorksWithoutRuntimeChineseTerms() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new WhitespaceAnalyzer()) {
            buildIndex(directory, analyzer,
                    String.join(" ", PinyinSupport.precomputedSuggestionTerms("俞俐均")),
                    String.join(" ", PinyinSupport.precomputedSuggestionTerms("俞俐均")),
                    String.join(" ", PinyinSupport.precomputedSuggestionTerms("月亮计划")));

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.SuggestionOption> corrections = suggester.suggestCorrections(
                        List.of("content"),
                        "俞利均",
                        new LuceneIndexSuggester.CorrectionConfig(0, 1, 2, 0, 5, 1, 0.5f, true));

                assertFalse(corrections.isEmpty());
                assertEquals("俞俐均", corrections.get(0).text());
            }
        }
    }

    @Test
    public void testPinyinPrefixSupportsMixedPartialFullAndInitialInput() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "影视飓风",
                    "影视飓风",
                    "影视飓风",
                    "影视剧风",
                    "影集积分");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestPrefixCompletions(
                        List.of("content"),
                        "yinsjf",
                        new LuceneIndexSuggester.CompletionConfig(5, 32, 1, 1, true, true));

                assertFalse(completions.isEmpty());
                assertEquals("影视飓风", completions.get(0).text());
            }
        }
    }

    @Test
    public void testPinyinPrewarmKeepsMixedChineseLatinMatches() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "战鹰",
                    "战鹰",
                    "战鹰",
                    "战影");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                suggester.prewarmPinyinIndices(List.of("content"));

                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestPrefixCompletions(
                        List.of("content"),
                        "战ying",
                        new LuceneIndexSuggester.CompletionConfig(3, 32, 1, 1, true, true));

                assertFalse(completions.isEmpty());
                assertEquals("战鹰", completions.get(0).text());
            }
        }
    }

    @Test
    public void testPinyinCorrectionSupportsHomophoneRepair() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "红警",
                    "红警",
                    "红警",
                    "红井盖");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.SuggestionOption> corrections = suggester.suggestCorrections(
                        List.of("content"),
                        "红井",
                        new LuceneIndexSuggester.CorrectionConfig(0, 1, 2, 0, 3, 1, 0.5f, true));

                assertFalse(corrections.isEmpty());
                assertTrue(corrections.toString(), corrections.stream().anyMatch(candidate -> candidate.text().equals("红警")));
            }
        }
    }

    @Test
    public void testPinyinCorrectionSupportsMixedChineseAndLatinInput() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "战鹰",
                    "战鹰",
                    "战鹰",
                    "战衣");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.SuggestionOption> corrections = suggester.suggestCorrections(
                        List.of("content"),
                        "战ying",
                        new LuceneIndexSuggester.CorrectionConfig(0, 1, 2, 0, 3, 1, 0.5f, true));

                assertFalse(corrections.isEmpty());
                assertTrue(corrections.toString(), corrections.stream().anyMatch(candidate -> candidate.text().equals("战鹰")));
            }
        }
    }

    @Test
    public void testPinyinCorrectionKeepsChineseAnchorAheadOfInitialsNoise() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "俞俐均",
                    "俞俐均",
                    "俞俐均",
                    "养老金",
                    "养老金",
                    "养老金",
                    "养老金",
                    "养老金",
                    "要牢记",
                    "要牢记",
                    "要牢记",
                    "月亮计划",
                    "月亮计划");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.SuggestionOption> corrections = suggester.suggestCorrections(
                        List.of("content"),
                        "俞利均",
                        new LuceneIndexSuggester.CorrectionConfig(0, 1, 2, 0, 5, 1, 0.5f, true));

                assertFalse(corrections.isEmpty());
                assertEquals("俞俐均", corrections.get(0).text());
            }
        }
    }

    @Test
    public void testPinyinPrefixKeepsChineseAnchorAheadOfInitialsNoise() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "影视飓风",
                    "影视飓风",
                    "影视飓风",
                    "影视剧风",
                    "影视剧风",
                    "益生菌粉",
                    "益生菌粉",
                    "益生菌粉",
                    "益生菌粉",
                    "益生菌粉");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestPrefixCompletions(
                        List.of("content"),
                        "影视jf",
                        new LuceneIndexSuggester.CompletionConfig(5, 32, 1, 1, true, true));

                assertFalse(completions.isEmpty());
                assertEquals("影视飓风", completions.get(0).text());
            }
        }
    }

    @Test
    public void testPureChinesePrefixPrefersLiteralPrefixOverHomophoneDocFreq() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "影视剧",
                    "影视剧",
                    "影视剧",
                    "影视剧",
                    "影视剧",
                    "影视剧",
                    "影视剧",
                    "影视剧",
                    "影视剧",
                    "影视剧",
                    "影视剧审美还是太权威了",
                    "影视剧审美还是太权威了",
                    "影视剧审美还是太权威了",
                    "影视飓风",
                    "影视飓风",
                    "影视飓风");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestPrefixCompletions(
                        List.of("content"),
                        "影视飓",
                        new LuceneIndexSuggester.CompletionConfig(5, 32, 1, 1, true, true));

                assertFalse(completions.isEmpty());
                assertEquals("影视飓风", completions.get(0).text());
            }
        }
    }

    @Test
    public void testPrefixNormalizationRemovesCjkAdjacentWhitespace() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "影 视飓风",
                    "俞 俐均",
                    "小米 su7");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestPrefixCompletions(
                        List.of("content"),
                        "影",
                        new LuceneIndexSuggester.CompletionConfig(5, 32, 1, 1, true, true));

                assertTrue(completions.stream().anyMatch(candidate -> candidate.text().equals("影视飓风")));
                assertFalse(completions.stream().anyMatch(candidate -> candidate.text().contains("影 视")));
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

    @Test
    public void testNextTokenPrefersInformativeContinuationOverSingleCharacter() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
            Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                "治愈 系",
                "治愈 系",
                "治愈 系",
                "治愈 时光",
                "治愈 时光",
                "治愈 时光",
                "治愈 时光",
                "治愈 时光",
                "系",
                "系",
                "系",
                "系",
                "系",
                "系",
                "时光",
                "时光",
                "时光",
                "时光",
                "时光");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestNextTokenCompletions(
                        List.of("content"),
                        "治愈",
                        new LuceneIndexSuggester.CompletionConfig(3, 32, 1, 1, true));

                assertFalse(completions.isEmpty());
                assertEquals("时光", completions.get(0).text());
                assertEquals(LuceneIndexSuggester.CompletionType.NEXT_TOKEN, completions.get(0).type());
                assertTrue(completions.stream().anyMatch(candidate -> candidate.text().equals("系")));
            }
        }
    }

    @Test
    public void testNextTokenSkipsCompactOverlapForSingleCharacterSeed() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "校园",
                    "校园生活",
                    "校园活动");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestNextTokenCompletions(
                        List.of("content"),
                        "校",
                        new LuceneIndexSuggester.CompletionConfig(5, 32, 1, 1, true));

                assertTrue(completions.isEmpty());
            }
        }
    }

    @Test
    public void testNextTokenRejectsWhitespaceSeparatedTail() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "三丽鸥\u3000库 洛米",
                    "三丽鸥家族",
                    "家族");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestNextTokenCompletions(
                        List.of("content"),
                        "三丽鸥",
                        new LuceneIndexSuggester.CompletionConfig(5, 32, 1, 1, true));

                assertFalse(completions.stream().anyMatch(candidate -> candidate.text().contains(" ")));
                assertTrue(completions.stream().anyMatch(candidate -> candidate.text().equals("家族")));
            }
        }
    }

    @Test
    public void testNextTokenRejectsOverlongPhraseTail() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "治愈 时光",
                    "治愈 时光",
                    "治愈 所有不开心",
                    "治愈 所有不开心",
                    "时光",
                    "时光",
                    "所有不开心");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestNextTokenCompletions(
                        List.of("content"),
                        "治愈",
                        new LuceneIndexSuggester.CompletionConfig(5, 32, 1, 1, true));

                assertTrue(completions.stream().anyMatch(candidate -> candidate.text().equals("时光")));
                assertFalse(completions.stream().anyMatch(candidate -> candidate.text().equals("所有不开心")));
            }
        }
    }

    @Test
    public void testNextTokenPenalizesFunctionWordHeavyTail() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "治愈 了我",
                    "治愈 了我",
                    "治愈 了我",
                    "治愈 了我",
                    "治愈 心灵",
                    "治愈 心灵",
                    "治愈 心灵",
                    "了我",
                    "了我",
                    "心灵");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestNextTokenCompletions(
                        List.of("content"),
                        "治愈",
                        new LuceneIndexSuggester.CompletionConfig(3, 32, 1, 1, true));

                assertFalse(completions.isEmpty());
                assertEquals("心灵", completions.get(0).text());
                assertTrue(completions.stream().anyMatch(candidate -> candidate.text().equals("了我")));
            }
        }
    }

    @Test
    public void testCorrectionSupportsConfiguredShortChineseTerm() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "网红零食",
                    "网红零食",
                    "网红小吃");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.SuggestionOption> corrections = suggester.suggestCorrections(
                        List.of("content"),
                        "网红零实",
                        new LuceneIndexSuggester.CorrectionConfig(0, 2, 2, 1, 3, 1, 0.5f));

                assertFalse(corrections.isEmpty());
                assertEquals("网红零食", corrections.get(0).text());
            }
        }
    }

    @Test
    public void testCorrectionFallsBackForShortChineseEntity() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "老师",
                    "老师",
                    "老师",
                    "老人",
                    "老人",
                    "老公");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.SuggestionOption> corrections = suggester.suggestCorrections(
                        List.of("content"),
                        "老狮",
                        new LuceneIndexSuggester.CorrectionConfig(0, 2, 2, 1, 3, 1, 0.5f));

                assertFalse(corrections.isEmpty());
                assertEquals("老师", corrections.get(0).text());
            }
        }
    }

    @Test
    public void testAutoUsesPrefixForShortChineseSeed() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "校园",
                    "校园",
                    "校园生活",
                    "老师",
                    "老师 今天");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.SuggestionOption> suggestions = suggester.suggestAuto(
                        List.of("content"),
                        "校",
                        new LuceneIndexSuggester.CompletionConfig(3, 32, 1, 1, true),
                        new LuceneIndexSuggester.CorrectionConfig(0, 2, 2, 1, 3, 1, 0.5f));

                assertFalse(suggestions.isEmpty());
                assertEquals("校园", suggestions.get(0).text());
            }
        }
    }

    @Test
    public void testAutoReturnsCorrectionForMisspelledChineseEntity() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "老师",
                    "老师",
                    "老师 今天",
                    "老人");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.SuggestionOption> suggestions = suggester.suggestAuto(
                        List.of("content"),
                        "老狮",
                        new LuceneIndexSuggester.CompletionConfig(3, 32, 1, 1, true),
                        new LuceneIndexSuggester.CorrectionConfig(0, 2, 2, 1, 3, 1, 0.5f));

                assertFalse(suggestions.isEmpty());
                assertEquals("老师", suggestions.get(0).text());
            }
        }
    }

    @Test
    public void testAutoReportsDominantSourceType() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "老师",
                    "老师",
                    "老师 今天",
                    "老人");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.SuggestionOption> suggestions = suggester.suggestAuto(
                        List.of("content"),
                        "老狮",
                        new LuceneIndexSuggester.CompletionConfig(3, 32, 1, 1, true),
                        new LuceneIndexSuggester.CorrectionConfig(0, 2, 2, 1, 3, 1, 0.5f));

                assertFalse(suggestions.isEmpty());
                assertEquals("老师", suggestions.get(0).text());
                assertEquals("correction", suggestions.get(0).type());
            }
        }
    }

    @Test
    public void testCorrectionPrefersSharedPrefixForShortChineseEntity() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "手机壳",
                    "手机壳",
                    "手机壳",
                    "手机",
                    "手书",
                    "手游");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.SuggestionOption> corrections = suggester.suggestCorrections(
                        List.of("content"),
                        "手机科",
                        new LuceneIndexSuggester.CorrectionConfig(0, 2, 2, 1, 5, 1, 0.5f));

                assertFalse(corrections.isEmpty());
                assertEquals("手机壳", corrections.get(0).text());
            }
        }
    }

    @Test
    public void testNextTokenKeepsInformativeSingleCharacterTailCompetitive() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
                Analyzer analyzer = new KeywordAnalyzer()) {
            buildIndex(directory, analyzer,
                    "安河 桥",
                    "安河 桥",
                    "安河 桥",
                    "安河 的",
                    "安河 的",
                    "桥",
                    "桥",
                    "的",
                    "的",
                    "的",
                    "的");

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LuceneIndexSuggester suggester = new LuceneIndexSuggester(reader);
                List<LuceneIndexSuggester.CompletionCandidate> completions = suggester.suggestNextTokenCompletions(
                        List.of("content"),
                        "安河",
                        new LuceneIndexSuggester.CompletionConfig(3, 32, 1, 1, true));

                assertFalse(completions.isEmpty());
                assertEquals("桥", completions.get(0).text());
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