package org.es.tok;

import org.es.tok.rules.SearchRules;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

/**
 * Tests for declude_suffixes feature in ES-TOK analyzer.
 * <p>
 * declude_suffixes removes tokens ending in a suffix (e.g. "的" or "了") when
 * the base form (without the suffix) also exists in the token list.
 * For example, if both "安静的" and "安静" exist, "安静的" is removed.
 * <p>
 * This replaces the old drop_deles feature; the logic is now integrated
 * into SearchRules as declude_suffixes.
 */
public class DropDelesTest {

    public static void main(String[] args) throws IOException {
        System.out.println("=== Declude Suffixes (formerly Drop Deles) ES-TOK Analyzer Tests ===\n");

        SearchRules decludeRules = new SearchRules(
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Arrays.asList("的", "了"));

        // Test 1: declude_suffixes enabled - "安静的" should be removed when "安静"
        // exists
        TestUtils.testAndPrintResults(
                "Test 1: declude_suffixes=['的','了'] - remove '安静的' when '安静' exists",
                "安静的生活",
                ConfigBuilder.create()
                        .withVocab("安静", "安静的", "生活")
                        .withCategSplitWord()
                        .withDropDuplicates()
                        .withRules(decludeRules)
                        .build());

        // Test 2: no declude rules - "安静的" should NOT be removed
        TestUtils.testAndPrintResults(
                "Test 2: no declude rules - keep '安静的'",
                "安静的生活",
                ConfigBuilder.create()
                        .withVocab("安静", "安静的", "生活")
                        .withCategSplitWord()
                        .withDropDuplicates()
                        .build());

        // Test 3: "了" suffix - "完成了" removed when "完成" exists
        TestUtils.testAndPrintResults(
                "Test 3: declude_suffixes with '了' suffix",
                "完成了任务",
                ConfigBuilder.create()
                        .withVocab("完成", "完成了", "任务")
                        .withCategSplitWord()
                        .withDropDuplicates()
                        .withRules(decludeRules)
                        .build());

        // Test 4: No base form exists - should keep the token
        TestUtils.testAndPrintResults(
                "Test 4: Keep token when base form doesn't exist",
                "了解问题",
                ConfigBuilder.create()
                        .withVocab("了解", "问题")
                        .withCategSplitWord()
                        .withDropDuplicates()
                        .withRules(decludeRules)
                        .build());

        // Test 5: Single char "的" or "了" - should not be affected (length <= suffix
        // length)
        TestUtils.testAndPrintResults(
                "Test 5: Single character '的' not dropped by declude_suffixes",
                "我的",
                ConfigBuilder.create()
                        .withVocab("我", "的")
                        .withCategSplitWord()
                        .withDropDuplicates()
                        .withRules(decludeRules)
                        .build());

        // Test 6: Both "的" and "了" suffixed tokens
        TestUtils.testAndPrintResults(
                "Test 6: Both '的' and '了' suffixed tokens",
                "美丽的花开了",
                ConfigBuilder.create()
                        .withVocab("美丽", "美丽的", "花", "开", "开了")
                        .withCategSplitWord()
                        .withDropDuplicates()
                        .withRules(decludeRules)
                        .build());

        // Test 7: With n-grams - declude should apply after all token generation
        TestUtils.testAndPrintResults(
                "Test 7: declude_suffixes with n-grams",
                "很安静的",
                ConfigBuilder.create()
                        .withVocab("很", "安静", "安静的")
                        .withCategSplitWord()
                        .withBigram()
                        .withDropDuplicates()
                        .withRules(decludeRules)
                        .build());
    }
}
