package org.es.tok;

import java.io.IOException;

/**
 * N-gram specific tests for ES-TOK analyzer
 */
public class NgramAnalyzerTest {

    public static void main(String[] args) throws IOException {
        System.out.println("=== N-gram ES-TOK Analyzer Tests ===\n");

        // Test 1: Basic bigram (categ + categ)
        TestUtils.testAndPrintResults(
                "Test 1: Bigram (categ + categ)",
                "deep learning machine",
                ConfigBuilder.create()
                        .withCateg()
                        .withBigram()
                        .withDropDuplicates()
                        .build());

        // Test 2: vcgram (vocab + vocab)
        TestUtils.testAndPrintResults(
                "Test 2: Vcgram (vocab + vocab)",
                "深度学习机器学习",
                ConfigBuilder.create()
                        .withVocab("深度", "学习", "机器")
                        .withCateg()
                        .withVcgram()
                        .withDropDuplicates()
                        .build());

        // Test 3: vbgram (vocab + categ)
        TestUtils.testAndPrintResults(
                "Test 3: Vbgram (vocab + categ)",
                "深度learning机器123",
                ConfigBuilder.create()
                        .withVocab("深度", "机器")
                        .withCateg()
                        .withVbgram()
                        .withDropDuplicates()
                        .build());

        // Test 4: All n-grams enabled
        TestUtils.testAndPrintResults(
                "Test 4: All N-grams",
                "深度 learning 机器 123 测试",
                ConfigBuilder.create()
                        .withVocab("深度", "机器", "测试")
                        .withCateg()
                        .withAllNgrams()
                        .withDropDuplicates()
                        .build());

        // Test 5: N-gram with separators
        TestUtils.testAndPrintResults(
                "Test 5: N-gram with Separators",
                "deep learning, test-case hello world",
                ConfigBuilder.create()
                        .withVocab("deep", "hello", "world")
                        .withCateg()
                        .withAllNgrams()
                        .withIgnoreCase()
                        .withDropDuplicates()
                        .build());

        // Test 6: Mixed language n-grams
        TestUtils.testAndPrintResults(
                "Test 6: Mixed Language N-grams",
                "越来越多的人选择了大语言模型(LLM)",
                ConfigBuilder.create()
                        .withVocab("越多", "越来越", "越来越多", "大语言模型", "语言", "模型", "LLM")
                        .withCategSplitWord()
                        .withAllNgrams()
                        .withIgnoreCase()
                        .withDropDuplicates()
                        .build());
    }
}
