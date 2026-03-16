package org.es.tok.core.analysis;

import org.es.tok.config.EsTokConfig;
import org.es.tok.support.ConfigBuilder;
import org.es.tok.support.TestUtils;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CoreAnalysisBehaviorTest {

    @Test
    public void testVocabBoundaryFilteringRejectsInnerAlnumMatch() throws Exception {
        EsTokConfig config = ConfigBuilder.create().withVocab("est").build();

        List<String> tokens = terms("testing", config);

        assertFalse(tokens.contains("est"));
    }

    @Test
    public void testVocabBoundaryFilteringAcceptsNaturalSegment() throws Exception {
        EsTokConfig config = ConfigBuilder.create().withVocab("123", "abc123", "def").build();

        List<String> tokens = terms("abc123def", config);

        assertTrue(tokens.contains("123"));
        assertTrue(tokens.contains("abc123"));
        assertTrue(tokens.contains("def"));
    }

    @Test
    public void testVocabConcatEmitsCollapsedSeparatorVariant() throws Exception {
        EsTokConfig config = ConfigBuilder.create()
                .withVocab("deep learning", "t-ara", "mask▂value")
                .build();

        List<String> tokens = terms("deep learning t-ara mask▂value", config);

        assertTrue(tokens.contains("deep learning"));
        assertTrue(tokens.contains("deeplearning"));
        assertTrue(tokens.contains("t-ara"));
        assertTrue(tokens.contains("tara"));
        assertTrue(tokens.contains("mask▂value"));
        assertTrue(tokens.contains("maskvalue"));
    }

    @Test
    public void testIgnoreHantNormalizesTraditionalChineseBeforeMatchingVocab() throws Exception {
        EsTokConfig config = ConfigBuilder.create()
                .withVocab("这是", "繁体字", "测试")
                .withCateg()
                .withIgnoreHant()
                .build();

        List<String> tokens = terms("這是繁體字測試", config);

        assertTrue(tokens.contains("这是"));
        assertTrue(tokens.contains("繁体字"));
        assertTrue(tokens.contains("测试"));
    }

    @Test
    public void testDropDuplicatesCollapsesEquivalentVocabAndCategTokens() throws Exception {
        EsTokConfig config = ConfigBuilder.create()
                .withVocab("Hello", "你好", "World")
                .withCateg()
                .withIgnoreCase()
                .withDropDuplicates()
                .build();

        List<String> tokens = terms("Hello你好World", config);

        assertEquals(1L, tokens.stream().filter("hello"::equals).count());
        assertEquals(1L, tokens.stream().filter("你好"::equals).count());
        assertEquals(1L, tokens.stream().filter("world"::equals).count());
    }

    @Test
    public void testDropVocabsKeepsOnlyNonVocabGroups() throws Exception {
        EsTokConfig config = ConfigBuilder.create()
                .withVocab("java")
                .withCategSplitWord()
                .withDropDuplicates()
                .withDropVocabs()
                .build();

        List<TestUtils.TokenInfo> tokens = TestUtils.analyze("java script", config);

        assertTrue(tokens.stream().noneMatch(token -> "vocab".equals(token.group())));
        assertTrue(tokens.stream().anyMatch(token -> "script".equals(token.token())));
    }

    @Test
    public void testBigramAndVcgramAreGeneratedWhenEnabled() throws Exception {
        EsTokConfig config = ConfigBuilder.create()
                .withVocab("深度", "学习")
                .withCateg()
                .withAllNgrams()
                .withDropDuplicates()
                .build();

        List<String> tokens = terms("深度学习", config);

        assertTrue(tokens.contains("深度"));
        assertTrue(tokens.contains("学习"));
        assertTrue(tokens.contains("深度学习"));
    }

    @Test
    public void testShortMixedAlphaNumericFragmentsDropWhenCoveredByCompactVocab() throws Exception {
        EsTokConfig config = ConfigBuilder.create()
                .withVocab("4k")
                .withCategSplitWord()
                .withDropDuplicates()
                .withIgnoreCase()
                .build();

        List<String> tokens = terms("4K", config);

        assertTrue(tokens.contains("4k"));
        assertFalse(tokens.contains("4"));
        assertFalse(tokens.contains("k"));
    }

    @Test
    public void testMixedAlphaNumericFragmentsStayWhenOneSideIsMeaningfulSegment() throws Exception {
        EsTokConfig config = ConfigBuilder.create()
                .withVocab("v2024")
                .withCategSplitWord()
                .withDropDuplicates()
                .withIgnoreCase()
                .build();

        List<String> tokens = terms("v2024", config);

        assertTrue(tokens.contains("v2024"));
        assertTrue(tokens.contains("v"));
        assertTrue(tokens.contains("2024"));
    }

    @Test
    public void testDropCogramControlsIntermediateCombinationTokens() throws Exception {
        EsTokConfig keepCograms = ConfigBuilder.create()
                .withVocab("你的", "名字", "你的名字")
                .withCategSplitWord()
                .withBigram()
                .withDropCogram(false)
                .build();
        EsTokConfig dropCograms = ConfigBuilder.create()
                .withVocab("你的", "名字", "你的名字")
                .withCategSplitWord()
                .withBigram()
                .withDropCogram(true)
                .build();

        List<String> kept = terms("你的名字", keepCograms);
        List<String> dropped = terms("你的名字", dropCograms);

        assertTrue(kept.size() >= dropped.size());
        assertTrue(kept.contains("你的名字"));
        assertTrue(dropped.contains("你的名字"));
    }

    private static List<String> terms(String text, EsTokConfig config) throws Exception {
        return TestUtils.analyze(text, config).stream()
                .map(TestUtils.TokenInfo::token)
                .collect(Collectors.toList());
    }
}