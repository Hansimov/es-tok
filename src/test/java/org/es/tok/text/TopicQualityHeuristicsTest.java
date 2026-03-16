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
    public void testFlattenStringValuesTraversesNestedCollections() {
        List<String> flattened = SourceValueUtils.flattenStringValues(List.of("alpha", List.of("beta", "", List.of("gamma"))));

        assertEquals(List.of("alpha", "beta", "gamma"), flattened);
    }

    @Test
    public void testFunctionWordRulesAreLoadedFromResources() {
        assertTrue(TopicQualityHeuristics.isFunctionWord('的'));
        assertFalse(TopicQualityHeuristics.isFunctionWord('警'));
    }
}