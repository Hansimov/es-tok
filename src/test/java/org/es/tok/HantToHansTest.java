package org.es.tok;

import java.io.IOException;

/**
 * Traditional Chinese to Simplified Chinese conversion tests for ES-TOK
 * analyzer
 */
public class HantToHansTest {

    public static void main(String[] args) throws IOException {
        System.out.println("=== Hant to Hans (Traditional to Simplified Chinese) ES-TOK Analyzer Tests ===\n");

        // Test 1: Basic traditional Chinese conversion disabled
        TestUtils.testAndPrintResults(
                "Test 1: Traditional Chinese WITHOUT conversion",
                "這是一個測試繁體轉簡體的例子",
                ConfigBuilder.create()
                        .withCateg()
                        .build());

        // Test 2: Basic traditional Chinese conversion enabled
        TestUtils.testAndPrintResults(
                "Test 2: Traditional Chinese WITH conversion",
                "這是一個測試繁體轉簡體的例子",
                ConfigBuilder.create()
                        .withCateg()
                        .withIgnoreHant()
                        .build());

        // Test 3: Mixed traditional and simplified Chinese
        TestUtils.testAndPrintResults(
                "Test 3: Mixed Traditional and Simplified WITH conversion",
                "這是测试繁體字和簡體字混合的文本",
                ConfigBuilder.create()
                        .withCateg()
                        .withIgnoreHant()
                        .build());

        // Test 4: Traditional Chinese with vocab
        TestUtils.testAndPrintResults(
                "Test 4: Traditional Chinese with Vocab WITH conversion",
                "這是繁體字測試",
                ConfigBuilder.create()
                        .withVocab("这是", "繁体字", "测试")
                        .withCateg()
                        .withIgnoreHant()
                        .build());

        // Test 5: Traditional Chinese with ignore case and conversion
        TestUtils.testAndPrintResults(
                "Test 5: Traditional Chinese with Ignore Case and Conversion",
                "這是繁體字HELLO測試World",
                ConfigBuilder.create()
                        .withVocab("这是", "繁体字", "hello", "测试", "world")
                        .withCateg()
                        .withIgnoreHant()
                        .withIgnoreCase()
                        .build());

        // Test 6: Traditional Chinese with all features
        TestUtils.testAndPrintResults(
                "Test 6: Traditional Chinese with All Features",
                "這是測試繁體轉簡體功能",
                ConfigBuilder.create()
                        .withVocab("这是", "测试", "繁体", "简体", "功能")
                        .withCateg()
                        .withAllNgrams()
                        .withIgnoreHant()
                        .withDropDuplicates()
                        .build());

        // Test 7: Text without traditional Chinese
        TestUtils.testAndPrintResults(
                "Test 7: Text without Traditional Chinese",
                "这是简体中文Hello World 123",
                ConfigBuilder.create()
                        .withCateg()
                        .withIgnoreHant()
                        .build());
    }
}
