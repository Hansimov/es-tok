package org.es.tok.text;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TopicQualityHeuristicsTest {

    @Test
    public void testSanitizeQueryTextRemovesUrlsAndNormalizesWhitespace() {
        String sanitized = TopicQualityHeuristics.sanitizeQueryText("  看这里 https://example.com/foo\nwww.test.com\t三体  ");

        assertEquals("看这里 三体", sanitized);
    }

    @Test
    public void testSanitizeQueryTextRemovesKnownBoilerplateFragments() {
        String sanitized = TopicQualityHeuristics.sanitizeQueryText("小企鹅这是在装傻吗 点点关注不错过 小企鹅持续更新咕嘎小日常系列中");

        assertFalse(sanitized.contains("点点关注"));
        assertFalse(sanitized.contains("持续更新"));
        assertFalse(sanitized.contains("小日常"));
        assertTrue(sanitized.contains("小企鹅这是在装傻吗"));
        assertTrue(sanitized.contains("小企鹅 咕嘎"));
    }

    @Test
    public void testSanitizeQueryTextStripsFormatCharacters() {
        String sanitized = TopicQualityHeuristics.sanitizeQueryText("\u2063 https://t.me/anemoya_chan");

        assertEquals("", sanitized);
    }

    @Test
    public void testOwnerSeedTermRejectsBoilerplateCjkTerm() {
        assertFalse(TopicQualityHeuristics.isUsefulOwnerQuerySeedTerm("最新视频"));
        assertFalse(TopicQualityHeuristics.isUsefulOwnerQuerySeedTerm("视频"));
        assertTrue(TopicQualityHeuristics.isUsefulOwnerQuerySeedTerm("黑神话"));
    }

    @Test
    public void testOwnerSeedPriorityPrefersStrongSpecificTerms() {
        int weak = TopicQualityHeuristics.ownerQuerySeedPriority("视频");
        int strong = TopicQualityHeuristics.ownerQuerySeedPriority("黑神话");

        assertTrue(strong > weak);
    }

    @Test
    public void testNormalizeOwnerNamesTightensChineseWhitespace() {
        assertEquals("三丽鸥", TextNormalization.normalizeOwnerDisplayName(" 三 丽 鸥 "));
        assertEquals("hello world", TextNormalization.normalizeOwnerLookupName(" Hello   World "));
    }

    @Test
    public void testNormalizeAnalyzedTokenPreservesAsciiSpacingButTightensChinese() {
        assertEquals("abc 123", TextNormalization.normalizeAnalyzedToken("  Abc   123 "));
        assertEquals("三丽鸥", TextNormalization.normalizeAnalyzedToken("三 丽 鸥"));
    }

    @Test
    public void testNormalizeSuggestionSurfaceUsesSharedWhitespaceRules() {
        assertEquals("三丽鸥", TextNormalization.normalizeSuggestionSurface(" 三 丽 鸥 "));
        assertEquals("Abc 123", TextNormalization.normalizeSuggestionSurface("  Abc   123 "));
    }

    @Test
    public void testFlattenStringValuesTraversesNestedCollections() {
        List<String> flattened = SourceValueUtils.flattenStringValues(List.of("alpha", List.of("beta", "", List.of("gamma"))));

        assertEquals(List.of("alpha", "beta", "gamma"), flattened);
    }

    @Test
    public void testFunctionWordRulesAreLoadedFromResources() {
        assertTrue(TopicQualityHeuristics.isFunctionWord('的'));
        assertFalse(TopicQualityHeuristics.isFunctionWord('警'));
    }

    @Test
    public void testOwnerSeedTermsApplyContextualDeclusion() {
        assertEquals(
                List.of("红色警戒", "政府"),
                List.copyOf(TopicQualityHeuristics.filterOwnerSeedTerms(List.of("红色警戒", "红色警戒的", "和政府", "政府"))));
    }

    @Test
    public void testAssociateCandidateTermsApplyContextualDeclusion() {
        assertEquals(
                List.of("政府", "教学"),
                List.copyOf(TopicQualityHeuristics.filterAssociateCandidateTerms(List.of("和政府", "政府", "教学"))));
    }
}