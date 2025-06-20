package org.es.tok;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.es.tok.tokenize.GroupAttribute;
import org.es.tok.analysis.EsTokAnalyzer;
import org.es.tok.config.EsTokConfig;
import org.es.tok.vocab.VocabConfig;
import org.es.tok.vocab.VocabFileLoader;
import org.es.tok.categ.CategConfig;
import org.es.tok.ngram.NgramConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class TestUnifiedEsTokAnalyzer {

    public static void main(String[] args) throws IOException {
        System.out.println("=== Unified ES-TOK Analyzer Test ===\n");

        String text = "123你好我的世界⻊⻌⽱⿑ Hello World Test-end GTA5 Chat GPT!@#ЗИЙЛМНЙατυφχψω幩槪𠆆𠇜";
        List<String> vocabs = Arrays.asList("你好", "你好我的", "我的世界", "hello world", "test", "end", "МНЙ", "υφχ");

        // Test 1: use_vocab
        System.out.println("=== Test 1: [vocab] ===");
        testAnalyzer(text, true, false, vocabs, false, false, false, null);

        // Test 2: use_categ + split_word
        System.out.println("=== Test 2: [categ, split_word] ===");
        testAnalyzer(text, false, true, vocabs, false, true, false, null);

        // Test 3: use_vocab + use_categ
        System.out.println("=== Test 3: [vocab, categ] ===");
        testAnalyzer(text, true, true, vocabs, false, false, false, null);

        // Test 4: use_vocab + use_categ + split_word
        System.out.println("=== Test 4: [vocab, categ, split_word] ===");
        testAnalyzer(text, true, true, vocabs, false, true, false, null);

        // Test 5: use_vocab + ignore_case
        System.out.println("=== Test 5: [vocab, ignore_case] ===");
        testAnalyzer(text, true, false, vocabs, true, false, false, null);

        // Test 6: use_vocab + vocab file
        System.out.println("=== Test 6: [vocab, ignore_case, vocab file] ===");
        String vocabFile = "/home/asimov/repos/bili-search-algo/models/sentencepiece/checkpoints/sp_merged.txt";
        Path vocabFilePath = Paths.get(vocabFile);
        if (Files.exists(vocabFilePath)) {
            List<String> fileVocabs = VocabFileLoader.loadVocabsFromFilePath(vocabFilePath);
            testAnalyzer(text, true, false, fileVocabs, true, false, false, null);
        } else {
            System.out.println("Vocab file not found: " + vocabFile);
        }

        // Test 7: Test drop_duplicates functionality
        testDropDuplicatesCases();

        // Test 8: N-gram tests
        testNgramCases();
    }

    private static void testDropDuplicatesCases() throws IOException {
        System.out.println("=== Drop Duplicates Test Cases ===\n");

        // Create test text with overlapping vocab and categ tokens
        String text1 = "Hello你好World";
        List<String> vocabs1 = Arrays.asList("Hello", "你好", "World"); // These will overlap with categ tokens

        // Test case 1: Without drop_duplicates (should have duplicates)
        System.out.println("=== Test 7.1: [vocab + categ] WITHOUT drop_duplicates ===");
        testAnalyzer(text1, true, true, vocabs1, true, false, false, null);

        // Test case 2: With drop_duplicates (should remove duplicates)
        System.out.println("=== Test 7.2: [vocab + categ] WITH drop_duplicates ===");
        testAnalyzer(text1, true, true, vocabs1, true, false, false, null);

        // Test case 3: More complex overlapping case
        String text2 = "深度learning机器123test";
        List<String> vocabs2 = Arrays.asList("深度", "learning", "机器", "123", "test");

        System.out.println("=== Test 7.3: [complex overlap] WITHOUT drop_duplicates ===");
        testAnalyzer(text2, true, true, vocabs2, false, false, true, null);

        System.out.println("=== Test 7.4: [complex overlap] WITH drop_duplicates ===");
        testAnalyzer(text2, true, true, vocabs2, false, false, true, null);

        // Test case 4: Drop duplicates with n-grams
        String text3 = "深度学习";
        List<String> vocabs3 = Arrays.asList("深度", "学习");
        NgramConfig ngramConfig = new NgramConfig(true, true, true, true);

        System.out.println("=== Test 7.5: [ngram overlap] WITHOUT drop_duplicates ===");
        testAnalyzer(text3, true, true, vocabs3, false, false, true, ngramConfig);

        System.out.println("=== Test 7.6: [ngram overlap] WITH drop_duplicates ===");
        testAnalyzer(text3, true, true, vocabs3, false, false, true, ngramConfig);
    }

    private static void testNgramCases() throws IOException {
        System.out.println("=== N-gram Test Cases ===\n");

        // Test case 1: Basic bigram (categ + categ)
        System.out.println("=== Test 8.1: [bigram - categ + categ] ===");
        String text1 = "deep learning machine";
        List<String> vocabs1 = Arrays.asList(); // No vocab words
        NgramConfig ngramConfig1 = new NgramConfig(true, true, false, false); // Only bigram
        testAnalyzerWithNgram(text1, false, true, vocabs1, false, false, true, ngramConfig1);

        // Test case 2: vcgram (vocab + vocab)
        System.out.println("=== Test 8.2: [vcgram - vocab + vocab] ===");
        String text2 = "深度学习机器学习";
        List<String> vocabs2 = Arrays.asList("深度", "学习", "机器");
        NgramConfig ngramConfig2 = new NgramConfig(true, false, true, false); // Only vcgram
        testAnalyzerWithNgram(text2, true, true, vocabs2, false, false, true, ngramConfig2);

        // Test case 3: vbgram (vocab + categ)
        System.out.println("=== Test 8.3: [vbgram - vocab + categ] ===");
        String text3 = "深度learning机器123";
        List<String> vocabs3 = Arrays.asList("深度", "机器");
        NgramConfig ngramConfig3 = new NgramConfig(true, false, false, true); // Only vbgram
        testAnalyzerWithNgram(text3, true, true, vocabs3, false, false, true, ngramConfig3);

        // Test case 4: All n-grams enabled
        System.out.println("=== Test 8.4: [all ngrams] ===");
        String text4 = "深度 learning 机器 123 测试";
        List<String> vocabs4 = Arrays.asList("深度", "机器", "测试");
        NgramConfig ngramConfig4 = new NgramConfig(true, true, true, true); // All n-grams
        testAnalyzerWithNgram(text4, true, true, vocabs4, false, false, true, ngramConfig4);

        // Test case 5: N-gram with separators (space, dash, etc.)
        System.out.println("=== Test 8.5: [ngram with separators] ===");
        String text5 = "deep learning test-case hello world";
        List<String> vocabs5 = Arrays.asList("deep", "hello", "world");
        NgramConfig ngramConfig5 = new NgramConfig(true, true, true, true); // All n-grams
        testAnalyzerWithNgram(text5, true, true, vocabs5, true, false, true, ngramConfig5);

        // Test case 6: N-gram interrupted by "nord" (punctuation)
        System.out.println("=== Test 8.6: [ngram interrupted by punct] ===");
        String text6 = "深度，学习！机器。测试";
        List<String> vocabs6 = Arrays.asList("深度", "学习", "机器", "测试");
        NgramConfig ngramConfig6 = new NgramConfig(true, true, true, true); // All n-grams
        testAnalyzerWithNgram(text6, true, true, vocabs6, false, false, true, ngramConfig6);

        // Test case 7: Mixed language n-grams
        System.out.println("=== Test 8.7: [mixed language ngrams] ===");
        String text7 = "越来越多的人选择了大语言模型(LLM)";
        List<String> vocabs7 = Arrays.asList("越多", "越来越", "越来越多", "大语言模型", "语言", "模型", "LLM");
        NgramConfig ngramConfig7 = new NgramConfig(true, true, true, true); // All n-grams
        testAnalyzerWithNgram(text7, true, true, vocabs7, true, true, true, ngramConfig7);

    }

    private static void testAnalyzer(
            String text, boolean useVocab, boolean useCateg, List<String> vocabs,
            boolean ignoreCase, boolean splitWord, boolean dropDuplicates, NgramConfig ngramConfig)
            throws IOException {

        System.out.println("Input:  " + text);
        System.out
                .println("Config: [useVocab=%b, useCateg=%b, ignoreCase=%b, splitWord=%b, dropDuplicates=%b]".formatted(
                        useVocab, useCateg, ignoreCase, splitWord, dropDuplicates));
        if (ngramConfig != null) {
            System.out.println("Ngram:  " + ngramConfig);
        }

        // Create configuration objects
        VocabConfig vocabConfig = new VocabConfig(useVocab, vocabs);
        CategConfig categConfig = new CategConfig(useCateg, splitWord);
        NgramConfig finalNgramConfig = ngramConfig != null ? ngramConfig : new NgramConfig(false, false, false, false);

        // Create unified config with all settings
        EsTokConfig config = new EsTokConfig(vocabConfig, categConfig, finalNgramConfig, ignoreCase, dropDuplicates);
        EsTokAnalyzer analyzer = new EsTokAnalyzer(config);

        try (analyzer) {
            TokenStream tokenStream = analyzer.tokenStream("field", text);

            CharTermAttribute termAtt = tokenStream.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAtt = tokenStream.addAttribute(OffsetAttribute.class);
            TypeAttribute typeAtt = tokenStream.addAttribute(TypeAttribute.class);
            GroupAttribute groupAtt = tokenStream.addAttribute(GroupAttribute.class);

            tokenStream.reset();

            System.out.println("Tokens:");
            int tokenIdx = 0;
            while (tokenStream.incrementToken()) {
                tokenIdx++;
                System.out.printf("  [%2d] [%2d-%2d] <%6s> (%5s): %s%n",
                        tokenIdx,
                        offsetAtt.startOffset(),
                        offsetAtt.endOffset(),
                        typeAtt.type(),
                        groupAtt.group(),
                        termAtt.toString());
            }

            if (tokenIdx == 0) {
                System.out.println("  No tokens found.");
            }

            tokenStream.end();
        }
        System.out.println();
    }

    private static void testAnalyzerWithNgram(
            String text, boolean useVocab, boolean useCateg, List<String> vocabs,
            boolean ignoreCase, boolean splitWord, boolean dropDuplicates, NgramConfig ngramConfig) throws IOException {
        testAnalyzer(text, useVocab, useCateg, vocabs, ignoreCase, splitWord, dropDuplicates, ngramConfig);
    }
}