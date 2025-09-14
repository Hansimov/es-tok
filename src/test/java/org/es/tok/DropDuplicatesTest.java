package org.es.tok;

import java.io.IOException;

/**
 * Drop duplicates functionality tests for ES-TOK analyzer
 */
public class DropDuplicatesTest {

    public static void main(String[] args) throws IOException {
        System.out.println("=== Drop Duplicates ES-TOK Analyzer Tests ===\n");

        // Test 1: Without drop_duplicates
        TestUtils.testAndPrintResults(
                "Test 1: Vocab + Categ WITHOUT Drop Duplicates",
                "Hello你好World",
                ConfigBuilder.create()
                        .withVocab("Hello", "你好", "World")
                        .withCateg()
                        .withIgnoreCase()
                        .build());

        // Test 2: With drop_duplicates
        TestUtils.testAndPrintResults(
                "Test 2: Vocab + Categ WITH Drop Duplicates",
                "Hello你好World",
                ConfigBuilder.create()
                        .withVocab("Hello", "你好", "World")
                        .withCateg()
                        .withIgnoreCase()
                        .withDropDuplicates()
                        .build());

        // Test 3: Complex overlap without drop_duplicates
        TestUtils.testAndPrintResults(
                "Test 3: Complex Overlap WITHOUT Drop Duplicates",
                "深度learning机器123test",
                ConfigBuilder.create()
                        .withVocab("深度", "learning", "机器", "123", "test")
                        .withCategSplitWord()
                        .build());

        // Test 4: Complex overlap with drop_duplicates
        TestUtils.testAndPrintResults(
                "Test 4: Complex Overlap WITH Drop Duplicates",
                "深度learning机器123test",
                ConfigBuilder.create()
                        .withVocab("深度", "learning", "机器", "123", "test")
                        .withCategSplitWord()
                        .withDropDuplicates()
                        .build());

        // Test 5: Drop duplicates with n-grams without
        TestUtils.testAndPrintResults(
                "Test 5: N-gram Overlap WITHOUT Drop Duplicates",
                "深度学习",
                ConfigBuilder.create()
                        .withVocab("深度", "学习")
                        .withCateg()
                        .withAllNgrams()
                        .withDropCogram(false)
                        .build());

        // Test 6: Drop duplicates with n-grams with
        TestUtils.testAndPrintResults(
                "Test 6: N-gram Overlap WITH Drop Duplicates",
                "深度学习",
                ConfigBuilder.create()
                        .withVocab("深度", "学习")
                        .withCateg()
                        .withAllNgrams()
                        .withDropDuplicates()
                        .build());
    }
}
