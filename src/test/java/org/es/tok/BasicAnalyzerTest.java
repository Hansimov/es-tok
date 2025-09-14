package org.es.tok;

import java.io.IOException;

/**
 * Basic functionality tests for ES-TOK analyzer
 */
public class BasicAnalyzerTest {

    public static void main(String[] args) throws IOException {
        System.out.println("=== Basic ES-TOK Analyzer Tests ===\n");

        String text = "123你好我的世界⻊⻌⽱⿑ Hello World Test-end GTA5 Chat GPT!@#ЗИЙЛМНЙατυφχψω幩槪𠆆𠇜";

        // Test 1: vocab only
        TestUtils.testAndPrintResults(
                "Test 1: Vocab Only",
                text,
                ConfigBuilder.create()
                        .withVocab("你好", "你好我的", "我的世界", "hello world", "test", "end", "МНЙ", "υφχ")
                        .build());

        // Test 2: categ with split_word
        TestUtils.testAndPrintResults(
                "Test 2: Categ with Split Word",
                text,
                ConfigBuilder.create()
                        .withCategSplitWord()
                        .build());

        // Test 3: vocab + categ
        TestUtils.testAndPrintResults(
                "Test 3: Vocab + Categ",
                text,
                ConfigBuilder.create()
                        .withVocab("你好", "你好我的", "我的世界", "hello world", "test", "end", "МНЙ", "υφχ")
                        .withCateg()
                        .build());

        // Test 4: vocab + categ + split_word
        TestUtils.testAndPrintResults(
                "Test 4: Vocab + Categ + Split Word",
                text,
                ConfigBuilder.create()
                        .withVocab("你好", "你好我的", "我的世界", "hello world", "test", "end", "МНЙ", "υφχ")
                        .withCategSplitWord()
                        .build());

        // Test 5: vocab + ignore_case
        TestUtils.testAndPrintResults(
                "Test 5: Vocab + Ignore Case",
                text,
                ConfigBuilder.create()
                        .withVocab("你好", "你好我的", "我的世界", "hello world", "test", "end", "МНЙ", "υφχ")
                        .withIgnoreCase()
                        .build());
    }
}
