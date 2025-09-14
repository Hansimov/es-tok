package org.es.tok;

import java.io.IOException;

/**
 * Cogram (drop_cogram) functionality tests for ES-TOK analyzer
 */
public class CogramTest {

    public static void main(String[] args) throws IOException {
        System.out.println("=== Cogram (Drop Cogram) ES-TOK Analyzer Tests ===\n");

        // Test 1: "你的名字" with drop_cogram = false
        TestUtils.testAndPrintResults(
                "Test 1: Drop Cogram = FALSE (should include cograms)",
                "你的名字",
                ConfigBuilder.create()
                        .withVocab("你的", "名字", "你的名字")
                        .withCategSplitWord()
                        .withBigram()
                        .withDropCogram(false)
                        .build());

        // Test 2: "你的名字" with drop_cogram = true
        TestUtils.testAndPrintResults(
                "Test 2: Drop Cogram = TRUE (should exclude cograms)",
                "你的名字",
                ConfigBuilder.create()
                        .withVocab("你的", "名字", "你的名字")
                        .withCategSplitWord()
                        .withBigram()
                        .withDropCogram(true)
                        .build());

        // Test 3: Another example with multiple vocabs
        TestUtils.testAndPrintResults(
                "Test 3: Multiple Vocabs with Drop Cogram = FALSE",
                "今天天气很好",
                ConfigBuilder.create()
                        .withVocab("今天", "天气", "很好")
                        .withCategSplitWord()
                        .withBigram()
                        .withDropCogram(false)
                        .build());

        // Test 4: Same example with drop_cogram = true
        TestUtils.testAndPrintResults(
                "Test 4: Multiple Vocabs with Drop Cogram = TRUE",
                "今天天气很好",
                ConfigBuilder.create()
                        .withVocab("今天", "天气", "很好")
                        .withCategSplitWord()
                        .withBigram()
                        .withDropCogram(true)
                        .build());

        // Test 5: Complex case with overlapping vocabs
        TestUtils.testAndPrintResults(
                "Test 5: Complex Overlap with Drop Cogram = FALSE",
                "深度学习机器学习",
                ConfigBuilder.create()
                        .withVocab("深度", "学习", "机器", "深度学习", "机器学习")
                        .withCategSplitWord()
                        .withBigram()
                        .withDropCogram(false)
                        .build());

        // Test 6: Same complex case with drop_cogram = true
        TestUtils.testAndPrintResults(
                "Test 6: Complex Overlap with Drop Cogram = TRUE",
                "深度学习机器学习",
                ConfigBuilder.create()
                        .withVocab("深度", "学习", "机器", "深度学习", "机器学习")
                        .withCategSplitWord()
                        .withBigram()
                        .withDropCogram(true)
                        .build());
    }
}
