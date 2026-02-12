package org.es.tok;

import org.es.tok.rules.AnalyzeRules;
import org.es.tok.rules.RulesConfig;
import org.es.tok.rules.RulesLoader;
import org.elasticsearch.common.settings.Settings;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;


/**
 * Unit tests for AnalyzeRules, RulesConfig, and RulesLoader
 */
public class AnalyzeRulesTest {

    // ===== AnalyzeRules basic tests =====

    @Test
    public void testEmptyRules() {
        AnalyzeRules rules = AnalyzeRules.EMPTY;
        assertTrue(rules.isEmpty());
        assertFalse(rules.shouldExclude("anything"));
        assertFalse(rules.shouldExclude(""));
    }

    @Test
    public void testExcludeTokensExactMatch() {
        AnalyzeRules rules = new AnalyzeRules(
                Arrays.asList("的", "了", "是"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        assertFalse(rules.isEmpty());
        assertTrue(rules.shouldExclude("的"));
        assertTrue(rules.shouldExclude("了"));
        assertTrue(rules.shouldExclude("是"));
        assertFalse(rules.shouldExclude("不"));
        assertFalse(rules.shouldExclude("的了"));
    }

    @Test
    public void testExcludePrefixes() {
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(),
                Arrays.asList("pre_", "test_"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        assertFalse(rules.isEmpty());
        assertTrue(rules.shouldExclude("pre_hello"));
        assertTrue(rules.shouldExclude("pre_"));
        assertTrue(rules.shouldExclude("test_case"));
        assertFalse(rules.shouldExclude("hello_pre_"));
        assertFalse(rules.shouldExclude("hello"));
    }

    @Test
    public void testExcludeSuffixes() {
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("_end", "_test"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        assertFalse(rules.isEmpty());
        assertTrue(rules.shouldExclude("hello_end"));
        assertTrue(rules.shouldExclude("_end"));
        assertTrue(rules.shouldExclude("my_test"));
        assertFalse(rules.shouldExclude("_end_more"));
        assertFalse(rules.shouldExclude("hello"));
    }

    @Test
    public void testExcludeContains() {
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("bad", "evil"),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        assertFalse(rules.isEmpty());
        assertTrue(rules.shouldExclude("bad"));
        assertTrue(rules.shouldExclude("very_bad_word"));
        assertTrue(rules.shouldExclude("badness"));
        assertTrue(rules.shouldExclude("evil_thing"));
        assertFalse(rules.shouldExclude("good"));
        assertFalse(rules.shouldExclude("hello"));
    }

    @Test
    public void testExcludePatterns() {
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("^\\d+$", "^test_.*_end$"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        assertFalse(rules.isEmpty());
        assertTrue(rules.shouldExclude("123"));
        assertTrue(rules.shouldExclude("456789"));
        assertTrue(rules.shouldExclude("test_middle_end"));
        assertFalse(rules.shouldExclude("abc123"));
        assertFalse(rules.shouldExclude("test_end"));
        assertFalse(rules.shouldExclude("hello"));
    }

    @Test
    public void testInvalidPatternIsIgnored() {
        // Invalid regex should be silently ignored
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("[invalid", "^valid$"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        // Should still work with the valid pattern
        assertTrue(rules.shouldExclude("valid"));
        assertFalse(rules.shouldExclude("invalid"));
    }

    @Test
    public void testCombinedRules() {
        AnalyzeRules rules = new AnalyzeRules(
                Arrays.asList("stop"),
                Arrays.asList("pre_"),
                Arrays.asList("_suf"),
                Arrays.asList("mid"),
                Arrays.asList("^\\d{3}$"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        // Exact match
        assertTrue(rules.shouldExclude("stop"));
        // Prefix match
        assertTrue(rules.shouldExclude("pre_hello"));
        // Suffix match
        assertTrue(rules.shouldExclude("hello_suf"));
        // Contains match
        assertTrue(rules.shouldExclude("has_mid_inside"));
        // Pattern match
        assertTrue(rules.shouldExclude("123"));

        // None match
        assertFalse(rules.shouldExclude("hello"));
        assertFalse(rules.shouldExclude("1234"));
    }

    @Test
    public void testNullTokenDoesNotCrash() {
        AnalyzeRules rules = new AnalyzeRules(
                Arrays.asList("test"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        // Null should not match anything
        assertFalse(rules.shouldExclude(null));
    }

    @Test
    public void testEmptyTokenString() {
        AnalyzeRules rules = new AnalyzeRules(
                Arrays.asList(""),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        // Empty strings are skipped by shouldExclude (null/empty guard)
        assertFalse(rules.shouldExclude(""));
        assertFalse(rules.shouldExclude(null));
    }

    @Test
    public void testGetters() {
        List<String> tokens = Arrays.asList("a", "b");
        List<String> prefixes = Arrays.asList("pre_");
        List<String> suffixes = Arrays.asList("_suf");
        List<String> contains = Arrays.asList("mid");
        List<String> patterns = Arrays.asList("^x$");

        AnalyzeRules rules = new AnalyzeRules(tokens, prefixes, suffixes, contains, patterns,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        assertEquals(tokens, rules.getExcludeTokens());
        assertEquals(prefixes, rules.getExcludePrefixes());
        assertEquals(suffixes, rules.getExcludeSuffixes());
        assertEquals(contains, rules.getExcludeContains());
        assertEquals(patterns, rules.getExcludePatterns());
    }

    @Test
    public void testGettersReturnUnmodifiable() {
        AnalyzeRules rules = new AnalyzeRules(
                Arrays.asList("a"),
                Arrays.asList("b"),
                Arrays.asList("c"),
                Arrays.asList("d"),
                Arrays.asList("e"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        try {
            rules.getExcludeTokens().add("x");
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    @Test
    public void testEqualsAndHashCode() {
        AnalyzeRules rules1 = new AnalyzeRules(
                Arrays.asList("a", "b"),
                Arrays.asList("pre_"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        AnalyzeRules rules2 = new AnalyzeRules(
                Arrays.asList("a", "b"),
                Arrays.asList("pre_"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        AnalyzeRules rules3 = new AnalyzeRules(
                Arrays.asList("c"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        assertEquals(rules1, rules2);
        assertEquals(rules1.hashCode(), rules2.hashCode());
        assertNotEquals(rules1, rules3);
    }

    @Test
    public void testToString() {
        AnalyzeRules rules = new AnalyzeRules(
                Arrays.asList("a"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        String str = rules.toString();
        assertTrue(str.contains("excludeTokens"));
        assertTrue(str.contains("a"));
    }

    // ===== RulesConfig tests =====

    @Test
    public void testRulesConfigDisabled() {
        RulesConfig config = new RulesConfig(false, AnalyzeRules.EMPTY);
        assertFalse(config.hasActiveRules());
        assertFalse(config.isUseRules());
    }

    @Test
    public void testRulesConfigEnabledButEmpty() {
        RulesConfig config = new RulesConfig(true, AnalyzeRules.EMPTY);
        assertFalse(config.hasActiveRules());
        assertTrue(config.isUseRules());
    }

    @Test
    public void testRulesConfigActive() {
        AnalyzeRules rules = new AnalyzeRules(
                Arrays.asList("stop"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        RulesConfig config = new RulesConfig(true, rules);
        assertTrue(config.hasActiveRules());
        assertTrue(config.isUseRules());
        assertEquals(rules, config.getAnalyzeRules());
    }

    // ===== RulesLoader tests =====

    @Test
    public void testLoadFromSettingsDisabled() {
        Settings settings = Settings.builder()
                .put("use_rules", false)
                .build();

        RulesConfig config = RulesLoader.loadRulesConfig(settings);
        assertFalse(config.isUseRules());
    }

    @Test
    public void testLoadFromSettingsInline() {
        Settings settings = Settings.builder()
                .put("use_rules", true)
                .putList("rules_config.exclude_tokens", "的", "了")
                .putList("rules_config.exclude_prefixes", "pre_")
                .putList("rules_config.exclude_suffixes", "_suf")
                .putList("rules_config.exclude_contains", "mid")
                .putList("rules_config.exclude_patterns", "^\\d+$")
                .build();

        RulesConfig config = RulesLoader.loadRulesConfig(settings);
        assertTrue(config.isUseRules());
        assertTrue(config.hasActiveRules());

        AnalyzeRules rules = config.getAnalyzeRules();
        assertEquals(2, rules.getExcludeTokens().size());
        assertEquals(1, rules.getExcludePrefixes().size());
        assertEquals(1, rules.getExcludeSuffixes().size());
        assertEquals(1, rules.getExcludeContains().size());
        assertEquals(1, rules.getExcludePatterns().size());

        assertTrue(rules.shouldExclude("的"));
        assertTrue(rules.shouldExclude("pre_hello"));
        assertTrue(rules.shouldExclude("hello_suf"));
        assertTrue(rules.shouldExclude("has_mid_in"));
        assertTrue(rules.shouldExclude("123"));
    }

    @Test
    public void testLoadFromSettingsNoConfig() {
        Settings settings = Settings.builder()
                .put("use_rules", true)
                .build();

        // When use_rules=true but no inline config and no file, falls back to inactive
        RulesConfig config = RulesLoader.loadRulesConfig(settings);
        // No valid rules were loaded (no file, no inline), so it falls back to disabled
        assertFalse(config.hasActiveRules());
    }

    @Test
    public void testLoadFromMapEmpty() {
        Map<String, Object> map = new HashMap<>();
        AnalyzeRules rules = RulesLoader.loadFromMap(map);
        assertTrue(rules.isEmpty());
    }

    @Test
    public void testLoadFromMapWithExcludeTokens() {
        Map<String, Object> map = new HashMap<>();
        map.put("exclude_tokens", Arrays.asList("stop1", "stop2"));

        AnalyzeRules rules = RulesLoader.loadFromMap(map);
        assertFalse(rules.isEmpty());
        assertTrue(rules.shouldExclude("stop1"));
        assertTrue(rules.shouldExclude("stop2"));
        assertFalse(rules.shouldExclude("keep"));
    }

    @Test
    public void testLoadFromMapAllFields() {
        Map<String, Object> map = new HashMap<>();
        map.put("exclude_tokens", Arrays.asList("exact"));
        map.put("exclude_prefixes", Arrays.asList("pre_"));
        map.put("exclude_suffixes", Arrays.asList("_suf"));
        map.put("exclude_contains", Arrays.asList("mid"));
        map.put("exclude_patterns", Arrays.asList("^\\d+$"));

        AnalyzeRules rules = RulesLoader.loadFromMap(map);
        assertTrue(rules.shouldExclude("exact"));
        assertTrue(rules.shouldExclude("pre_test"));
        assertTrue(rules.shouldExclude("test_suf"));
        assertTrue(rules.shouldExclude("has_mid_val"));
        assertTrue(rules.shouldExclude("999"));
        assertFalse(rules.shouldExclude("keep_this"));
    }

    @Test
    public void testLoadFromMapNullValues() {
        Map<String, Object> map = new HashMap<>();
        map.put("exclude_tokens", null);

        AnalyzeRules rules = RulesLoader.loadFromMap(map);
        assertTrue(rules.isEmpty());
    }

    // ===== ConfigBuilder integration tests =====

    @Test
    public void testConfigBuilderWithRules() {
        AnalyzeRules rules = new AnalyzeRules(
                Arrays.asList("stop"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        org.es.tok.config.EsTokConfig config = ConfigBuilder.create()
                .withRules(rules)
                .build();

        assertTrue(config.getRulesConfig().hasActiveRules());
        assertTrue(config.getRulesConfig().getAnalyzeRules().shouldExclude("stop"));
        assertFalse(config.getRulesConfig().getAnalyzeRules().shouldExclude("keep"));
    }

    @Test
    public void testConfigBuilderWithExcludeTokens() {
        org.es.tok.config.EsTokConfig config = ConfigBuilder.create()
                .withExcludeTokens("a", "b", "c")
                .build();

        assertTrue(config.getRulesConfig().hasActiveRules());
        assertTrue(config.getRulesConfig().getAnalyzeRules().shouldExclude("a"));
        assertFalse(config.getRulesConfig().getAnalyzeRules().shouldExclude("d"));
    }

    @Test
    public void testConfigBuilderDefaultNoRules() {
        org.es.tok.config.EsTokConfig config = ConfigBuilder.create().build();

        assertFalse(config.getRulesConfig().hasActiveRules());
    }

    // ===== Chinese token filtering tests =====

    @Test
    public void testChineseExcludeTokens() {
        AnalyzeRules rules = new AnalyzeRules(
                Arrays.asList("的", "了", "是", "在", "和"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        assertTrue(rules.shouldExclude("的"));
        assertTrue(rules.shouldExclude("了"));
        assertTrue(rules.shouldExclude("是"));
        assertFalse(rules.shouldExclude("测试"));
        assertFalse(rules.shouldExclude("文档"));
    }

    @Test
    public void testChineseExcludePrefixes() {
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(),
                Arrays.asList("不"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        assertTrue(rules.shouldExclude("不好"));
        assertTrue(rules.shouldExclude("不行"));
        assertFalse(rules.shouldExclude("好不好"));
    }

    @Test
    public void testChineseExcludeContains() {
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("脏"),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        assertTrue(rules.shouldExclude("肮脏"));
        assertTrue(rules.shouldExclude("脏话"));
        assertTrue(rules.shouldExclude("很脏的"));
        assertFalse(rules.shouldExclude("干净"));
    }

    @Test
    public void testChineseExcludePatterns() {
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("^[一二三四五六七八九十]+$"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        assertTrue(rules.shouldExclude("一"));
        assertTrue(rules.shouldExclude("二三"));
        assertTrue(rules.shouldExclude("十"));
        assertFalse(rules.shouldExclude("一个"));
        assertFalse(rules.shouldExclude("第三"));
    }

    // ===== Edge case tests =====

    @Test
    public void testLargeExcludeList() {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            tokens.add("token_" + i);
        }
        AnalyzeRules rules = new AnalyzeRules(
                tokens,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        assertTrue(rules.shouldExclude("token_0"));
        assertTrue(rules.shouldExclude("token_999"));
        assertFalse(rules.shouldExclude("token_1000"));
    }

    @Test
    public void testSpecialCharactersInTokens() {
        AnalyzeRules rules = new AnalyzeRules(
                Arrays.asList("hello.world", "test[0]", "a+b"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        assertTrue(rules.shouldExclude("hello.world"));
        assertTrue(rules.shouldExclude("test[0]"));
        assertTrue(rules.shouldExclude("a+b"));
    }

    @Test
    public void testEmptyStringsInLists() {
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(),
                Arrays.asList(""),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        // Empty prefix matches all strings (everything starts with "")
        assertTrue(rules.shouldExclude("anything"));
    }

    @Test
    public void testMultiplePatternsOnSameToken() {
        AnalyzeRules rules = new AnalyzeRules(
                Arrays.asList("exact_match"),
                Arrays.asList("exact"), // also matches by prefix
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        // Should match via either rule
        assertTrue(rules.shouldExclude("exact_match"));
        assertTrue(rules.shouldExclude("exact_other"));
    }

    // ===== Include rules tests =====

    @Test
    public void testIncludeTokensOverrideExclude() {
        AnalyzeRules rules = new AnalyzeRules(
                Arrays.asList("的", "了", "是"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("的"), // include exact token "的"
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        // "的" is in both exclude and include tokens → include wins
        assertFalse(rules.shouldExclude("的"));
        // "了" is only in exclude
        assertTrue(rules.shouldExclude("了"));
        assertTrue(rules.shouldExclude("是"));
    }

    @Test
    public void testIncludePrefixesOverrideExcludePrefixes() {
        // exclude prefix "的" but include prefix "的确","的哥"
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(),
                Arrays.asList("的"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("的确", "的哥"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        // "的人" starts with "的" → excluded, not included → excluded
        assertTrue(rules.shouldExclude("的人"));
        // "的确" starts with "的" → excluded, but starts with "的确" → included → kept
        assertFalse(rules.shouldExclude("的确"));
        assertFalse(rules.shouldExclude("的确如此"));
        // "的哥" starts with "的" → excluded, but starts with "的哥" → included → kept
        assertFalse(rules.shouldExclude("的哥"));
        // "的士" starts with "的" → excluded, not in include → excluded
        assertTrue(rules.shouldExclude("的士"));
        // No prefix match at all
        assertFalse(rules.shouldExclude("安静"));
    }

    @Test
    public void testIncludeSuffixesOverrideExclude() {
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("了"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("了解"),
                Collections.emptyList(),
                Collections.emptyList());
        // "完了" ends with "了" → excluded, does not end with "了解" → excluded
        assertTrue(rules.shouldExclude("完了"));
        // "了解" ends with "了" → excluded, ends with "了解" → included → kept
        assertFalse(rules.shouldExclude("了解"));
    }

    @Test
    public void testIncludeContainsOverrideExclude() {
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("bad"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("badminton"),
                Collections.emptyList());
        // "verybad" contains "bad" → excluded, does not contain "badminton" → excluded
        assertTrue(rules.shouldExclude("verybad"));
        // "badminton" contains "bad" → excluded, contains "badminton" → included → kept
        assertFalse(rules.shouldExclude("badminton"));
        assertFalse(rules.shouldExclude("playbadminton"));
    }

    @Test
    public void testIncludePatternsOverrideExclude() {
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("^\\d+$"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("^100$"));
        // "123" all digits → excluded, not "100" → excluded
        assertTrue(rules.shouldExclude("123"));
        // "100" all digits → excluded, matches include pattern → included → kept
        assertFalse(rules.shouldExclude("100"));
    }

    @Test
    public void testIncludeOnlyDoesNotFilter() {
        // Only include rules, no exclude rules → isEmpty() returns true
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("keep_this"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        assertTrue(rules.isEmpty()); // no exclude rules = no filtering
        assertFalse(rules.shouldExclude("anything"));
    }

    @Test
    public void testChineseIncludeOverrideExclude() {
        // Real-world rule: exclude prefix "的" and "了", include specific exceptions
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(),
                Arrays.asList("的", "了"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("的确", "的卢", "的哥", "的士",
                        "了不", "了断", "了解", "了得", "了却", "了结",
                        "了然", "了如", "了若", "了无", "了事", "了悟", "了了"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        // "的" itself: starts with "的" → excluded. Does "的" start with "的确"? No → not
        // included → excluded
        assertTrue(rules.shouldExclude("的"));
        // "了" itself: same logic
        assertTrue(rules.shouldExclude("了"));
        // "的确": starts with "的" → excluded. starts with "的确" → included → kept
        assertFalse(rules.shouldExclude("的确"));
        // "了解": starts with "了" → excluded. starts with "了解" → included → kept
        assertFalse(rules.shouldExclude("了解"));
        // "了了": starts with "了" → excluded. starts with "了了" → included → kept
        assertFalse(rules.shouldExclude("了了"));
        // "的人": starts with "的" → excluded. No include match → excluded
        assertTrue(rules.shouldExclude("的人"));
        // "了吧": starts with "了" → excluded. No include match → excluded
        assertTrue(rules.shouldExclude("了吧"));
        // "安静": no match at all → kept
        assertFalse(rules.shouldExclude("安静"));
    }

    @Test
    public void testIncludeGetters() {
        List<String> iTokens = Arrays.asList("a");
        List<String> iPrefixes = Arrays.asList("b");
        List<String> iSuffixes = Arrays.asList("c");
        List<String> iContains = Arrays.asList("d");
        List<String> iPatterns = Arrays.asList("e");

        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                iTokens, iPrefixes, iSuffixes, iContains, iPatterns);

        assertEquals(iTokens, rules.getIncludeTokens());
        assertEquals(iPrefixes, rules.getIncludePrefixes());
        assertEquals(iSuffixes, rules.getIncludeSuffixes());
        assertEquals(iContains, rules.getIncludeContains());
        assertEquals(iPatterns, rules.getIncludePatterns());
    }

    @Test
    public void testEqualsWithIncludeFields() {
        AnalyzeRules rules1 = new AnalyzeRules(
                Arrays.asList("x"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Arrays.asList("y"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        AnalyzeRules rules2 = new AnalyzeRules(
                Arrays.asList("x"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Arrays.asList("y"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        AnalyzeRules rules3 = new AnalyzeRules(
                Arrays.asList("x"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Arrays.asList("z"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        assertEquals(rules1, rules2);
        assertEquals(rules1.hashCode(), rules2.hashCode());
        assertNotEquals(rules1, rules3);
    }

    @Test
    public void testLoadFromSettingsWithInclude() {
        Settings settings = Settings.builder()
                .put("use_rules", true)
                .putList("rules_config.exclude_prefixes", "的")
                .putList("rules_config.include_prefixes", "的确", "的士")
                .build();

        RulesConfig config = RulesLoader.loadRulesConfig(settings);
        assertTrue(config.isUseRules());
        assertTrue(config.hasActiveRules());

        AnalyzeRules rules = config.getAnalyzeRules();
        assertFalse(rules.shouldExclude("的确"));
        assertTrue(rules.shouldExclude("的人"));
    }

    @Test
    public void testLoadFromMapWithInclude() {
        Map<String, Object> map = new HashMap<>();
        map.put("exclude_prefixes", Arrays.asList("的"));
        map.put("include_prefixes", Arrays.asList("的确", "的士"));

        AnalyzeRules rules = RulesLoader.loadFromMap(map);
        assertFalse(rules.shouldExclude("的确"));
        assertFalse(rules.shouldExclude("的士"));
        assertTrue(rules.shouldExclude("的人"));
    }

    // ===== Declude rules tests =====

    @Test
    public void testDecludeSuffixes() {
        // "安静的" should be excluded when "安静" exists in allTokenTexts
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Arrays.asList("的"));

        Set<String> allTokenTexts = new HashSet<>(Arrays.asList("安静", "安静的", "世界"));

        // "安静的" ends with "的" and "安静" exists → declude (exclude)
        assertTrue(rules.shouldExclude("安静的", allTokenTexts));
        // "世界" does not end with "的" → not decluded
        assertFalse(rules.shouldExclude("世界", allTokenTexts));
        // "安静" does not end with "的" → not decluded
        assertFalse(rules.shouldExclude("安静", allTokenTexts));
        // Without context, shouldExclude(token) does NOT check declude
        assertFalse(rules.shouldExclude("安静的"));
    }

    @Test
    public void testDecludePrefixes() {
        // "不安静" should be excluded when "安静" exists in allTokenTexts
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("不"), Collections.emptyList());

        Set<String> allTokenTexts = new HashSet<>(Arrays.asList("安静", "不安静", "世界"));

        // "不安静" starts with "不" and "安静" exists → declude (exclude)
        assertTrue(rules.shouldExclude("不安静", allTokenTexts));
        // "世界" does not start with "不" → not decluded
        assertFalse(rules.shouldExclude("世界", allTokenTexts));
        // "安静" does not start with "不" → not decluded
        assertFalse(rules.shouldExclude("安静", allTokenTexts));
        // "不" alone: length == prefix length, so not matched (must be longer)
        assertFalse(rules.shouldExclude("不", allTokenTexts));
    }

    @Test
    public void testDecludeWithIncludeOverride() {
        // Include rules take priority over declude
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("安静的"), // include exact token
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Arrays.asList("的")); // declude suffix

        Set<String> allTokenTexts = new HashSet<>(Arrays.asList("安静", "安静的", "高兴的"));

        // "安静的" matches include → kept (not excluded)
        assertFalse(rules.shouldExclude("安静的", allTokenTexts));
        // "高兴的" ends with "的" and "高兴" exists... but "高兴" is NOT in allTokenTexts
        assertFalse(rules.shouldExclude("高兴的", allTokenTexts));

        // Add "高兴" to the set
        Set<String> allTokenTexts2 = new HashSet<>(Arrays.asList("安静", "安静的", "高兴", "高兴的"));
        // "高兴的" ends with "的" and "高兴" exists → decluded (excluded)
        assertTrue(rules.shouldExclude("高兴的", allTokenTexts2));
        // "安静的" still included
        assertFalse(rules.shouldExclude("安静的", allTokenTexts2));
    }

    @Test
    public void testDecludeGetters() {
        List<String> dPrefixes = Arrays.asList("不", "没");
        List<String> dSuffixes = Arrays.asList("的", "了");

        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                dPrefixes, dSuffixes);

        assertEquals(dPrefixes, rules.getDecludePrefixes());
        assertEquals(dSuffixes, rules.getDecludeSuffixes());
    }

    @Test
    public void testEqualsWithDecludeFields() {
        AnalyzeRules rules1 = new AnalyzeRules(
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("不"), Arrays.asList("的"));
        AnalyzeRules rules2 = new AnalyzeRules(
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("不"), Arrays.asList("的"));
        AnalyzeRules rules3 = new AnalyzeRules(
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("没"), Arrays.asList("了"));

        assertEquals(rules1, rules2);
        assertEquals(rules1.hashCode(), rules2.hashCode());
        assertNotEquals(rules1, rules3);
    }

    @Test
    public void testIsEmptyWithDecludeFields() {
        // Declude-only rules: isEmpty() should return false
        AnalyzeRules rules = new AnalyzeRules(
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Arrays.asList("的"));
        assertFalse(rules.isEmpty());

        AnalyzeRules rules2 = new AnalyzeRules(
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("不"), Collections.emptyList());
        assertFalse(rules2.isEmpty());
    }

    @Test
    public void testLoadFromSettingsWithDeclude() {
        Settings settings = Settings.builder()
                .put("use_rules", true)
                .putList("rules_config.exclude_tokens", "的")
                .putList("rules_config.declude_suffixes", "的")
                .putList("rules_config.declude_prefixes", "不")
                .build();

        RulesConfig config = RulesLoader.loadRulesConfig(settings);
        assertTrue(config.isUseRules());
        assertTrue(config.hasActiveRules());

        AnalyzeRules rules = config.getAnalyzeRules();
        assertEquals(Arrays.asList("的"), rules.getDecludeSuffixes());
        assertEquals(Arrays.asList("不"), rules.getDecludePrefixes());

        // Exclude still works
        assertTrue(rules.shouldExclude("的"));

        // Declude works with context
        Set<String> allTokenTexts = new HashSet<>(Arrays.asList("安静", "安静的"));
        assertTrue(rules.shouldExclude("安静的", allTokenTexts));
    }

    @Test
    public void testLoadFromMapWithDeclude() {
        Map<String, Object> map = new HashMap<>();
        map.put("declude_suffixes", Arrays.asList("的", "了"));
        map.put("declude_prefixes", Arrays.asList("不"));

        AnalyzeRules rules = RulesLoader.loadFromMap(map);
        assertEquals(Arrays.asList("的", "了"), rules.getDecludeSuffixes());
        assertEquals(Arrays.asList("不"), rules.getDecludePrefixes());

        Set<String> allTokenTexts = new HashSet<>(Arrays.asList("安静", "安静的", "走", "走了"));
        assertTrue(rules.shouldExclude("安静的", allTokenTexts));
        assertTrue(rules.shouldExclude("走了", allTokenTexts));
        assertFalse(rules.shouldExclude("安静", allTokenTexts));
    }
}
