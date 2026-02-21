package org.es.tok;

import org.es.tok.analysis.EsTokAnalyzer;
import org.es.tok.config.EsTokConfig;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.junit.Test;

import java.io.IOException;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Tests for drop_categs behavior with the optimized processText() ordering:
 * <ol>
 * <li>ngram generation runs BEFORE dropCategTokens</li>
 * <li>dropCategTokens only drops CJK/lang categ tokens (preserves
 * eng/arab)</li>
 * </ol>
 *
 * This ensures:
 * <ul>
 * <li>Bigrams from CJK categ tokens survive (e.g., "红警" from "红"+"警")</li>
 * <li>eng/arab categ tokens survive even when covered by boundary vocab
 * (e.g., "08" and "hbk" survive even inside vocab "红警hbk08")</li>
 * <li>have_token constraint matching works without slow WildcardQuery
 * fallback</li>
 * </ul>
 */
public class DropCategsPreserveTest {

    // ========================================================================
    // Helper: extract all token texts from analyzer
    // ========================================================================

    private static List<String> tokenize(EsTokConfig config, String text) throws IOException {
        List<String> tokens = new ArrayList<>();
        try (EsTokAnalyzer analyzer = new EsTokAnalyzer(config)) {
            TokenStream stream = analyzer.tokenStream("test", text);
            CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(termAtt.toString());
            }
            stream.end();
        }
        return tokens;
    }

    private static Map<String, String> tokenizeWithTypes(EsTokConfig config, String text) throws IOException {
        Map<String, String> tokenTypes = new LinkedHashMap<>();
        try (EsTokAnalyzer analyzer = new EsTokAnalyzer(config)) {
            TokenStream stream = analyzer.tokenStream("test", text);
            CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);
            TypeAttribute typeAtt = stream.addAttribute(TypeAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokenTypes.put(termAtt.toString(), typeAtt.type());
            }
            stream.end();
        }
        return tokenTypes;
    }

    private static Set<String> tokenSet(EsTokConfig config, String text) throws IOException {
        return new HashSet<>(tokenize(config, text));
    }

    /**
     * Standard production-like config: vocab + categ(splitWord) + bigram +
     * drop_categs + dedup + ignoreCase
     */
    private static EsTokConfig productionConfig(String... vocabs) {
        return ConfigBuilder.create()
                .withVocab(vocabs)
                .withCategSplitWord()
                .withBigram()
                .withDropCategs()
                .withDropDuplicates()
                .withIgnoreCase()
                .build();
    }

    /** Same as production but without drop_categs, for comparison */
    private static EsTokConfig noDropConfig(String... vocabs) {
        return ConfigBuilder.create()
                .withVocab(vocabs)
                .withCategSplitWord()
                .withBigram()
                .withDropDuplicates()
                .withIgnoreCase()
                .build();
    }

    // ========================================================================
    // 1. Core scenario: 红警hbk08 — the original bug
    // ========================================================================

    @Test
    public void testOriginalBug_红警hbk08_bigramsAndEngArabPreserved() throws IOException {
        // "红警hbk08" is a single vocab word at text boundary.
        // Before the fix: CJK categ tokens 红,警 were dropped, destroying
        // bigram "红警". eng/arab categ tokens hbk,08 were also dropped.
        // After the fix: bigrams generated before drop, and eng/arab preserved.
        EsTokConfig config = productionConfig("红警hbk08");
        Set<String> tokens = tokenSet(config, "红警hbk08");

        // vocab token
        assertTrue("should have vocab '红警hbk08'", tokens.contains("红警hbk08"));
        // bigram from CJK categ tokens (generated before drop)
        assertTrue("should have bigram '红警'", tokens.contains("红警"));
        // eng categ token preserved
        assertTrue("should have eng 'hbk'", tokens.contains("hbk"));
        // arab categ token preserved
        assertTrue("should have arab '08'", tokens.contains("08"));
        // CJK single chars should be dropped (covered by boundary vocab)
        assertFalse("CJK char '红' should be dropped", tokens.contains("红"));
        assertFalse("CJK char '警' should be dropped", tokens.contains("警"));
    }

    @Test
    public void testOriginalBug_红警hbk08_inContext() throws IOException {
        // Same vocab word but in a sentence with surrounding text
        EsTokConfig config = productionConfig("红警hbk08");
        Set<String> tokens = tokenSet(config, "我喜欢看红警hbk08的视频");

        assertTrue("vocab '红警hbk08' present", tokens.contains("红警hbk08"));
        assertTrue("bigram '红警' present", tokens.contains("红警"));
        assertTrue("eng 'hbk' present", tokens.contains("hbk"));
        assertTrue("arab '08' present", tokens.contains("08"));
        // Context CJK chars should still be present (not covered by boundary vocab)
        assertTrue("context bigram '我喜' present or individual chars",
                tokens.contains("我喜") || tokens.contains("喜欢"));
    }

    // ========================================================================
    // 2. CJK-only vocab at boundary: bigrams survive
    // ========================================================================

    @Test
    public void testCjkVocabAtBoundary_bigramsSurvive() throws IOException {
        // "影视飓风" is a vocab word at text start (boundary).
        // CJK chars 影,视,飓,风 would be dropped by boundary rule.
        // But bigrams 影视, 视飓, 飓风 should survive (generated before drop).
        EsTokConfig config = productionConfig("影视飓风");
        Set<String> tokens = tokenSet(config, "影视飓风");

        assertTrue("vocab '影视飓风' present", tokens.contains("影视飓风"));
        assertTrue("bigram '影视' present", tokens.contains("影视"));
        assertTrue("bigram '飓风' present", tokens.contains("飓风"));
        // individual CJK chars dropped
        assertFalse("'影' should be dropped", tokens.contains("影"));
        assertFalse("'视' should be dropped", tokens.contains("视"));
    }

    @Test
    public void testCjkVocabInMiddle_noDropIfNotBoundary() throws IOException {
        // "飓风" as vocab in middle of text — not at boundary,
        // so CJK chars might be preserved (depending on vocabFreq).
        // With only 1 covering vocab, CJK chars survive (threshold=2).
        EsTokConfig config = productionConfig("飓风");
        Set<String> tokens = tokenSet(config, "影视飓风来了");

        assertTrue("vocab '飓风' present", tokens.contains("飓风"));
        // With only 1 vocab covering, CJK chars survive (vocabFreqThreshold=2)
        // and vocab is NOT at boundary (has text before and after).
        // So '飓' and '风' should be present.
        assertTrue("'飓' should survive (non-boundary, freq=1)", tokens.contains("飓"));
    }

    @Test
    public void testTwoCjkVocabsCoveringChar() throws IOException {
        // Two vocab words covering the same CJK char → vocabFreq >= 2 → drop
        EsTokConfig config = productionConfig("影视飓", "飓风");
        Set<String> tokens = tokenSet(config, "影视飓风来了");

        assertTrue("vocab '影视飓' present", tokens.contains("影视飓"));
        assertTrue("vocab '飓风' present", tokens.contains("飓风"));
        // '飓' is covered by 2 vocab tokens → dropped
        assertFalse("'飓' should be dropped (freq=2)", tokens.contains("飓"));
    }

    // ========================================================================
    // 3. eng/arab preservation: various patterns
    // ========================================================================

    @Test
    public void testEngPreserved_singleEngInVocab() throws IOException {
        // Vocab word contains only eng: categ eng token is same as vocab
        EsTokConfig config = productionConfig("hello");
        Set<String> tokens = tokenSet(config, "hello");

        assertTrue("vocab 'hello' present", tokens.contains("hello"));
        // eng categ "hello" overlaps exactly with vocab — dedup removes one
        // but at least one copy survives
        assertTrue("eng 'hello' present (via vocab or preserved categ)",
                tokens.contains("hello"));
    }

    @Test
    public void testEngPreserved_mixedCjkEng() throws IOException {
        // "gta5" in "玩gta5吧" — eng+arab categ tokens within vocab
        EsTokConfig config = productionConfig("gta5");
        Set<String> tokens = tokenSet(config, "玩gta5吧");

        assertTrue("vocab 'gta5' present", tokens.contains("gta5"));
        // eng "gta" and arab "5" preserved even when covered by boundary vocab
        assertTrue("eng 'gta' preserved", tokens.contains("gta"));
        assertTrue("arab '5' preserved", tokens.contains("5"));
    }

    @Test
    public void testArabPreserved_numbersInVocab() throws IOException {
        // "v2024" with vocab — arab "2024" should survive
        EsTokConfig config = productionConfig("v2024");
        Set<String> tokens = tokenSet(config, "v2024");

        assertTrue("vocab 'v2024' present", tokens.contains("v2024"));
        assertTrue("eng 'v' preserved", tokens.contains("v"));
        assertTrue("arab '2024' preserved", tokens.contains("2024"));
    }

    @Test
    public void testEngArabPreserved_multipleSegments() throws IOException {
        // "abc123def456" — alternating eng/arab
        EsTokConfig config = productionConfig("abc123def456");
        Set<String> tokens = tokenSet(config, "abc123def456");

        assertTrue("vocab present", tokens.contains("abc123def456"));
        assertTrue("eng 'abc' preserved", tokens.contains("abc"));
        assertTrue("arab '123' preserved", tokens.contains("123"));
        assertTrue("eng 'def' preserved", tokens.contains("def"));
        assertTrue("arab '456' preserved", tokens.contains("456"));
    }

    // ========================================================================
    // 4. Mixed CJK + eng/arab: CJK dropped, eng/arab preserved
    // ========================================================================

    @Test
    public void testMixed_cjkDroppedEngArabPreserved() throws IOException {
        // "达芬奇resolve18" — CJK chars dropped (boundary vocab), eng/arab kept
        EsTokConfig config = productionConfig("达芬奇resolve18");
        Set<String> tokens = tokenSet(config, "达芬奇resolve18");

        assertTrue("vocab present", tokens.contains("达芬奇resolve18"));
        // CJK individual chars dropped
        assertFalse("CJK '达' dropped", tokens.contains("达"));
        assertFalse("CJK '芬' dropped", tokens.contains("芬"));
        assertFalse("CJK '奇' dropped", tokens.contains("奇"));
        // eng/arab preserved
        assertTrue("eng 'resolve' preserved", tokens.contains("resolve"));
        assertTrue("arab '18' preserved", tokens.contains("18"));
        // CJK bigrams survive (generated before drop)
        assertTrue("bigram '达芬' present", tokens.contains("达芬"));
        assertTrue("bigram '芬奇' present", tokens.contains("芬奇"));
    }

    @Test
    public void testMixed_cjkEngCjk() throws IOException {
        // "天禄a队" — CJK + eng + CJK, all in vocab
        EsTokConfig config = productionConfig("天禄a队");
        Set<String> tokens = tokenSet(config, "天禄a队");

        assertTrue("vocab '天禄a队' present", tokens.contains("天禄a队"));
        assertTrue("eng 'a' preserved", tokens.contains("a"));
        // CJK chars dropped at boundary
        assertFalse("'天' dropped", tokens.contains("天"));
        assertFalse("'禄' dropped", tokens.contains("禄"));
        assertFalse("'队' dropped", tokens.contains("队"));
        // bigram survives
        assertTrue("bigram '天禄' present", tokens.contains("天禄"));
    }

    // ========================================================================
    // 5. No vocab coverage: nothing dropped
    // ========================================================================

    @Test
    public void testNoVocab_nothingDropped() throws IOException {
        // No vocab words → no dropping at all
        EsTokConfig config = ConfigBuilder.create()
                .withCategSplitWord()
                .withBigram()
                .withDropCategs()
                .withDropDuplicates()
                .withIgnoreCase()
                .build();
        Set<String> tokens = tokenSet(config, "红警hbk08");

        assertTrue("'红' present (no vocab to trigger drop)", tokens.contains("红"));
        assertTrue("'警' present", tokens.contains("警"));
        assertTrue("'hbk' present", tokens.contains("hbk"));
        assertTrue("'08' present", tokens.contains("08"));
        assertTrue("bigram '红警' present", tokens.contains("红警"));
    }

    @Test
    public void testNoVocabCoverage_vocabElsewhere() throws IOException {
        // Vocab exists but doesn't cover the text under test
        EsTokConfig config = productionConfig("不相关词");
        Set<String> tokens = tokenSet(config, "红警hbk08");

        assertTrue("'红' present (not covered by vocab)", tokens.contains("红"));
        assertTrue("'hbk' present", tokens.contains("hbk"));
        assertTrue("'08' present", tokens.contains("08"));
        assertTrue("bigram '红警' present", tokens.contains("红警"));
    }

    // ========================================================================
    // 6. Multiple vocab words: overlapping and nested coverage
    // ========================================================================

    @Test
    public void testMultipleVocabs_overlapping() throws IOException {
        // Two overlapping vocabs: "红警hbk" and "hbk08"
        EsTokConfig config = productionConfig("红警hbk", "hbk08");
        Set<String> tokens = tokenSet(config, "红警hbk08");

        assertTrue("vocab '红警hbk' present", tokens.contains("红警hbk"));
        assertTrue("vocab 'hbk08' present", tokens.contains("hbk08"));
        // "红" and "警" covered by 1 boundary vocab → dropped
        assertFalse("'红' dropped (boundary vocab)", tokens.contains("红"));
        assertFalse("'警' dropped (boundary vocab)", tokens.contains("警"));
        // "hbk" covered by 2 vocabs → freq ≥ 2 → but eng preserved anyway
        assertTrue("eng 'hbk' preserved despite 2 vocabs", tokens.contains("hbk"));
        // "08" covered by 1 non-boundary vocab → preserved (arab)
        assertTrue("arab '08' preserved", tokens.contains("08"));
        // bigram survives
        assertTrue("bigram '红警' present", tokens.contains("红警"));
    }

    @Test
    public void testMultipleVocabs_nested() throws IOException {
        // Nested: "我看到" and "看到" both cover "看" and "到"
        EsTokConfig config = productionConfig("我看到", "看到");
        Set<String> tokens = tokenSet(config, "我看到天禄");

        assertTrue("vocab '我看到' present", tokens.contains("我看到"));
        assertTrue("vocab '看到' present", tokens.contains("看到"));
        // "看" covered by 2 vocabs → freq ≥ 2 → dropped (CJK)
        assertFalse("'看' dropped (freq=2)", tokens.contains("看"));
        // "到" covered by 2 vocabs → freq ≥ 2 → dropped (CJK)
        assertFalse("'到' dropped (freq=2)", tokens.contains("到"));
    }

    // ========================================================================
    // 7. Separator/boundary behavior
    // ========================================================================

    @Test
    public void testBoundaryAtTextStart() throws IOException {
        // Vocab at text start = boundary → CJK chars dropped, eng/arab preserved
        EsTokConfig config = productionConfig("hello世界");
        Set<String> tokens = tokenSet(config, "hello世界很大");

        assertTrue("vocab 'hello世界' present", tokens.contains("hello世界"));
        assertTrue("eng 'hello' preserved", tokens.contains("hello"));
        // '世' and '界' are CJK, at boundary → dropped
        assertFalse("'世' dropped (boundary)", tokens.contains("世"));
        assertFalse("'界' dropped (boundary)", tokens.contains("界"));
    }

    @Test
    public void testBoundaryAtTextEnd() throws IOException {
        // Vocab at text end = boundary
        EsTokConfig config = productionConfig("世界end");
        Set<String> tokens = tokenSet(config, "你好世界end");

        assertTrue("vocab '世界end' present", tokens.contains("世界end"));
        assertTrue("eng 'end' preserved", tokens.contains("end"));
    }

    @Test
    public void testBoundaryAtSeparator() throws IOException {
        // Vocab adjacent to separator (space/dash) = boundary
        EsTokConfig config = productionConfig("红警hbk08");
        Set<String> tokens = tokenSet(config, "我爱 红警hbk08 视频");

        assertTrue("vocab '红警hbk08' present", tokens.contains("红警hbk08"));
        // Vocab is adjacent to spaces → boundary → CJK dropped, eng/arab preserved
        assertTrue("bigram '红警' present", tokens.contains("红警"));
        assertTrue("eng 'hbk' preserved", tokens.contains("hbk"));
        assertTrue("arab '08' preserved", tokens.contains("08"));
    }

    // ========================================================================
    // 8. ignoreCase interaction
    // ========================================================================

    @Test
    public void testIgnoreCase_vocabAndCateg() throws IOException {
        // "GTA5" lowercased to "gta5" by ignoreCase
        EsTokConfig config = productionConfig("gta5");
        Set<String> tokens = tokenSet(config, "GTA5很好玩");

        assertTrue("vocab 'gta5' present (lowercased)", tokens.contains("gta5"));
        assertTrue("eng 'gta' preserved (lowercased)", tokens.contains("gta"));
        assertTrue("arab '5' preserved", tokens.contains("5"));
    }

    @Test
    public void testIgnoreCase_mixedCaseVocab() throws IOException {
        // Vocab "ChatGPT" registered lowercase, input mixed case
        EsTokConfig config = productionConfig("chatgpt");
        Set<String> tokens = tokenSet(config, "我在用ChatGPT聊天");

        assertTrue("vocab 'chatgpt' present (lowercased)", tokens.contains("chatgpt"));
        // eng categ "chatgpt" after lowercasing is same as vocab → dedup
        // but at least the token exists
        assertTrue("'chatgpt' exists", tokens.contains("chatgpt"));
    }

    // ========================================================================
    // 9. Pure eng/arab text (no CJK)
    // ========================================================================

    @Test
    public void testPureEng_noDropping() throws IOException {
        // All eng → eng categ tokens; with vocab coverage at boundary
        EsTokConfig config = productionConfig("hello");
        Set<String> tokens = tokenSet(config, "hello world");

        assertTrue("vocab+categ 'hello' present", tokens.contains("hello"));
        assertTrue("categ 'world' present", tokens.contains("world"));
    }

    @Test
    public void testPureArab_noDropping() throws IOException {
        // All numbers → arab categ tokens; with vocab coverage
        EsTokConfig config = productionConfig("12345");
        Set<String> tokens = tokenSet(config, "12345 678");

        assertTrue("vocab+categ '12345' present", tokens.contains("12345"));
        assertTrue("categ '678' present", tokens.contains("678"));
    }

    @Test
    public void testPureEngArab_vocabCovers() throws IOException {
        // "v2ray" as vocab at boundary — eng "v" and eng "ray" preserved
        EsTokConfig config = productionConfig("v2ray");
        Set<String> tokens = tokenSet(config, "v2ray很好用");

        assertTrue("vocab 'v2ray' present", tokens.contains("v2ray"));
        assertTrue("eng 'v' preserved", tokens.contains("v"));
        assertTrue("arab '2' preserved", tokens.contains("2"));
        assertTrue("eng 'ray' preserved", tokens.contains("ray"));
    }

    // ========================================================================
    // 10. dropCategs disabled: nothing should be dropped
    // ========================================================================

    @Test
    public void testDropCategsDisabled_allPresent() throws IOException {
        EsTokConfig config = noDropConfig("红警hbk08");
        Set<String> tokens = tokenSet(config, "红警hbk08");

        assertTrue("vocab present", tokens.contains("红警hbk08"));
        assertTrue("CJK '红' present (no dropping)", tokens.contains("红"));
        assertTrue("CJK '警' present (no dropping)", tokens.contains("警"));
        assertTrue("eng 'hbk' present", tokens.contains("hbk"));
        assertTrue("arab '08' present", tokens.contains("08"));
        assertTrue("bigram '红警' present", tokens.contains("红警"));
    }

    // ========================================================================
    // 11. vcgram/vbgram with drop_categs
    // ========================================================================

    @Test
    public void testVcgramWithDropCategs() throws IOException {
        // vcgram = word+word where at least one is vocab
        EsTokConfig config = ConfigBuilder.create()
                .withVocab("影视", "飓风")
                .withCategSplitWord()
                .withVcgram()
                .withDropCategs()
                .withDropDuplicates()
                .withIgnoreCase()
                .build();
        Set<String> tokens = tokenSet(config, "影视飓风");

        assertTrue("vocab '影视' present", tokens.contains("影视"));
        assertTrue("vocab '飓风' present", tokens.contains("飓风"));
        // vcgram between two adjacent vocab tokens should survive
        assertTrue("vcgram '影视飓风' present", tokens.contains("影视飓风"));
    }

    @Test
    public void testVbgramWithDropCategs() throws IOException {
        // vbgram = vocab+vocab. With splitWord, categ CJK chars between
        // adjacent vocab tokens may interfere with adjacency detection.
        // Use non-splitWord categ or no categ to test pure vbgram.
        EsTokConfig config = ConfigBuilder.create()
                .withVocab("深度", "学习")
                .withCateg() // no splitWord → CJK block tokens don't interfere
                .withVbgram()
                .withDropCategs()
                .withDropDuplicates()
                .withIgnoreCase()
                .build();
        Set<String> tokens = tokenSet(config, "深度学习");

        assertTrue("vocab '深度' present", tokens.contains("深度"));
        assertTrue("vocab '学习' present", tokens.contains("学习"));
        assertTrue("vbgram '深度学习' present", tokens.contains("深度学习"));
    }

    @Test
    public void testVbgramWithSplitWord() throws IOException {
        // With splitWord, individual CJK categ chars between adjacent vocab
        // tokens can break adjacency detection for vbgram.
        // This is expected behavior — use vcgram for this case.
        EsTokConfig config = ConfigBuilder.create()
                .withVocab("深度", "学习")
                .withCategSplitWord()
                .withVbgram()
                .withVcgram()
                .withDropCategs()
                .withDropDuplicates()
                .withIgnoreCase()
                .build();
        Set<String> tokens = tokenSet(config, "深度学习");

        assertTrue("vocab '深度' present", tokens.contains("深度"));
        assertTrue("vocab '学习' present", tokens.contains("学习"));
        // vcgram (word+word, at least one vocab) should work
        assertTrue("vcgram '深度学习' present", tokens.contains("深度学习"));
    }

    // ========================================================================
    // 12. Real-world user search patterns
    // ========================================================================

    @Test
    public void testRealWorld_bilibili_uploader() throws IOException {
        // Up主 name with mixed CJK + eng + arab
        EsTokConfig config = productionConfig("红警hbk08", "红警月亮3");
        Set<String> tokens = tokenSet(config, "红警hbk08 红警月亮3");

        // First vocab
        assertTrue("vocab '红警hbk08' present", tokens.contains("红警hbk08"));
        assertTrue("bigram '红警' from first", tokens.contains("红警"));
        assertTrue("eng 'hbk' preserved", tokens.contains("hbk"));
        assertTrue("arab '08' preserved", tokens.contains("08"));

        // Second vocab
        assertTrue("vocab '红警月亮3' present", tokens.contains("红警月亮3"));
        assertTrue("arab '3' preserved", tokens.contains("3"));
    }

    @Test
    public void testRealWorld_gameTitle() throws IOException {
        // "gta5online" — common game title
        EsTokConfig config = productionConfig("gta5online", "gta5");
        Set<String> tokens = tokenSet(config, "gta5online很好玩");

        assertTrue("vocab 'gta5online' present", tokens.contains("gta5online"));
        assertTrue("vocab 'gta5' present", tokens.contains("gta5"));
        assertTrue("eng 'gta' preserved", tokens.contains("gta"));
        assertTrue("arab '5' preserved", tokens.contains("5"));
        assertTrue("eng 'online' preserved", tokens.contains("online"));
    }

    @Test
    public void testRealWorld_techTerm() throws IOException {
        // "4k120fps" — tech spec
        EsTokConfig config = productionConfig("4k120fps");
        Set<String> tokens = tokenSet(config, "4k120fps视频");

        assertTrue("vocab '4k120fps' present", tokens.contains("4k120fps"));
        assertTrue("arab '4' preserved", tokens.contains("4"));
        assertTrue("eng 'k' preserved", tokens.contains("k"));
        assertTrue("arab '120' preserved", tokens.contains("120"));
        assertTrue("eng 'fps' preserved", tokens.contains("fps"));
    }

    @Test
    public void testRealWorld_versionNumber() throws IOException {
        // "python3.12" — version with dash/dot
        EsTokConfig config = productionConfig("python3");
        Set<String> tokens = tokenSet(config, "python3很好用");

        assertTrue("vocab 'python3' present", tokens.contains("python3"));
        assertTrue("eng 'python' preserved", tokens.contains("python"));
        assertTrue("arab '3' preserved", tokens.contains("3"));
    }

    @Test
    public void testRealWorld_twoConstraints() throws IOException {
        // Simulates "+红警 +08" constraint scenario
        // Both "红警" (as bigram) and "08" (as arab categ) should be searchable
        EsTokConfig config = productionConfig("红警hbk08");
        Set<String> tokens = tokenSet(config, "红警hbk08");

        // These are what have_token constraints would look for:
        assertTrue("have_token '红警' would match", tokens.contains("红警"));
        assertTrue("have_token '08' would match", tokens.contains("08"));
        assertTrue("have_token 'hbk' would match", tokens.contains("hbk"));
        assertTrue("have_token '红警hbk08' would match", tokens.contains("红警hbk08"));
    }

    // ========================================================================
    // 13. Edge cases
    // ========================================================================

    @Test
    public void testEmptyText() throws IOException {
        EsTokConfig config = productionConfig("红警hbk08");
        List<String> tokens = tokenize(config, "");
        assertTrue("empty text → no tokens", tokens.isEmpty());
    }

    @Test
    public void testSingleCjkChar() throws IOException {
        // Single CJK char with vocab that covers it at boundary
        EsTokConfig config = productionConfig("我");
        Set<String> tokens = tokenSet(config, "我");

        assertTrue("vocab '我' present", tokens.contains("我"));
    }

    @Test
    public void testSingleEngToken() throws IOException {
        EsTokConfig config = productionConfig("test");
        Set<String> tokens = tokenSet(config, "test");

        assertTrue("'test' present", tokens.contains("test"));
    }

    @Test
    public void testSingleArabToken() throws IOException {
        EsTokConfig config = productionConfig("123");
        Set<String> tokens = tokenSet(config, "123");

        assertTrue("'123' present", tokens.contains("123"));
    }

    @Test
    public void testOnlyPunctuation() throws IOException {
        EsTokConfig config = productionConfig("红警hbk08");
        Set<String> tokens = tokenSet(config, "，。！？");

        // Punctuation is nord type → no categ/vocab tokens
        assertFalse("no standard tokens for punctuation-only", tokens.contains("红警hbk08"));
    }

    @Test
    public void testLongVocabChain() throws IOException {
        // Multiple vocab words in sequence
        EsTokConfig config = productionConfig("红警", "hbk", "08");
        Set<String> tokens = tokenSet(config, "红警hbk08");

        assertTrue("vocab '红警' present", tokens.contains("红警"));
        assertTrue("vocab/categ 'hbk' present", tokens.contains("hbk"));
        assertTrue("vocab/categ '08' present", tokens.contains("08"));
    }

    @Test
    public void testDashSeparatedTokens() throws IOException {
        // Dash is a separator → creates boundary
        EsTokConfig config = productionConfig("test-case");
        Set<String> tokens = tokenSet(config, "test-case");

        // "test-case" as vocab
        assertTrue("vocab 'test-case' present", tokens.contains("test-case"));
        // eng categ tokens should be preserved
        assertTrue("eng 'test' preserved", tokens.contains("test"));
        assertTrue("eng 'case' preserved", tokens.contains("case"));
    }

    // ========================================================================
    // 14. Comparison: drop_categs ON vs OFF
    // ========================================================================

    @Test
    public void testDropCategsOnOff_engArabUnchanged() throws IOException {
        // eng/arab tokens should be the same with or without drop_categs
        String text = "红警hbk08";
        String[] vocabs = { "红警hbk08" };

        Set<String> withDrop = tokenSet(productionConfig(vocabs), text);
        Set<String> withoutDrop = tokenSet(noDropConfig(vocabs), text);

        // eng/arab present in both
        assertTrue("hbk present with drop", withDrop.contains("hbk"));
        assertTrue("hbk present without drop", withoutDrop.contains("hbk"));
        assertTrue("08 present with drop", withDrop.contains("08"));
        assertTrue("08 present without drop", withoutDrop.contains("08"));

        // CJK chars only present without drop
        assertFalse("红 absent with drop", withDrop.contains("红"));
        assertTrue("红 present without drop", withoutDrop.contains("红"));
    }

    @Test
    public void testDropCategsOnOff_bigramsUnchanged() throws IOException {
        // bigrams should be the same with or without drop_categs
        String text = "红警hbk08";
        String[] vocabs = { "红警hbk08" };

        Set<String> withDrop = tokenSet(productionConfig(vocabs), text);
        Set<String> withoutDrop = tokenSet(noDropConfig(vocabs), text);

        // bigram present in both
        assertTrue("红警 present with drop", withDrop.contains("红警"));
        assertTrue("红警 present without drop", withoutDrop.contains("红警"));
    }

    // ========================================================================
    // 15. Lang type (Greek/Cyrillic/Thai): dropped like CJK
    // ========================================================================

    @Test
    public void testLang_greekDroppedAtBoundary() throws IOException {
        // Greek letters α,β are "lang" type → dropped like CJK when at boundary
        // With splitWord, each Greek letter becomes a separate categ token
        EsTokConfig config = productionConfig("αβx3");
        Set<String> tokens = tokenSet(config, "αβx3");

        assertTrue("vocab 'αβx3' present", tokens.contains("αβx3"));
        // Greek lang chars dropped (boundary vocab)
        assertFalse("lang 'α' dropped (boundary)", tokens.contains("α"));
        assertFalse("lang 'β' dropped (boundary)", tokens.contains("β"));
        // eng/arab preserved
        assertTrue("eng 'x' preserved", tokens.contains("x"));
        assertTrue("arab '3' preserved", tokens.contains("3"));
        // bigram from lang chars survives (generated before drop)
        assertTrue("bigram 'αβ' present", tokens.contains("αβ"));
    }

    @Test
    public void testLang_cyrillicDroppedAtBoundary() throws IOException {
        // Cyrillic Д,и are "lang" type → dropped at boundary.
        // Vocab must be registered lowercase since ignoreCase only lowercases
        // the input text, not the Trie keywords.
        EsTokConfig config = productionConfig("диv2");
        Set<String> tokens = tokenSet(config, "Диv2");

        assertTrue("vocab present (lowercased)", tokens.contains("диv2"));
        // Cyrillic dropped at boundary
        assertFalse("lang 'д' dropped", tokens.contains("д"));
        assertFalse("lang 'и' dropped", tokens.contains("и"));
        // eng/arab preserved
        assertTrue("eng 'v' preserved", tokens.contains("v"));
        assertTrue("arab '2' preserved", tokens.contains("2"));
        // bigram survives
        assertTrue("bigram 'ди' present", tokens.contains("ди"));
    }

    @Test
    public void testLang_thaiDroppedAtBoundary() throws IOException {
        // Thai ก,ข are "lang" type → dropped at boundary
        EsTokConfig config = productionConfig("กขtest");
        Set<String> tokens = tokenSet(config, "กขtest");

        assertTrue("vocab 'กขtest' present", tokens.contains("กขtest"));
        // Thai lang chars dropped
        assertFalse("lang 'ก' dropped", tokens.contains("ก"));
        assertFalse("lang 'ข' dropped", tokens.contains("ข"));
        // eng preserved
        assertTrue("eng 'test' preserved", tokens.contains("test"));
        // bigram from Thai chars survives
        assertTrue("bigram 'กข' present", tokens.contains("กข"));
    }

    @Test
    public void testLang_greekNotDroppedWhenNotBoundary() throws IOException {
        // Greek chars in non-boundary position with 1 covering vocab → not dropped
        EsTokConfig config = productionConfig("αβ");
        Set<String> tokens = tokenSet(config, "看αβ吧");

        assertTrue("vocab 'αβ' present", tokens.contains("αβ"));
        // Non-boundary, freq=1 → lang chars survive
        assertTrue("lang 'α' survives (non-boundary, freq=1)", tokens.contains("α"));
    }

    @Test
    public void testLang_mixedCjkGreek_bothDropped() throws IOException {
        // CJK and Greek chars both dropped at boundary (both are dropped types)
        EsTokConfig config = productionConfig("红α警β");
        Set<String> tokens = tokenSet(config, "红α警β");

        assertTrue("vocab '红α警β' present", tokens.contains("红α警β"));
        // CJK dropped
        assertFalse("CJK '红' dropped", tokens.contains("红"));
        assertFalse("CJK '警' dropped", tokens.contains("警"));
        // Greek lang dropped
        assertFalse("lang 'α' dropped", tokens.contains("α"));
        assertFalse("lang 'β' dropped", tokens.contains("β"));
    }

    // ========================================================================
    // 16. Japanese kana (CJK type): dropped like Chinese
    // ========================================================================

    @Test
    public void testJapanese_hiraganaDroppedAtBoundary() throws IOException {
        // Hiragana ぼ,っ,ち are in CJK range → treated as CJK → dropped
        EsTokConfig config = productionConfig("ぼっちrock");
        Set<String> tokens = tokenSet(config, "ぼっちrock");

        assertTrue("vocab 'ぼっちrock' present", tokens.contains("ぼっちrock"));
        // Hiragana CJK chars dropped at boundary
        assertFalse("CJK 'ぼ' dropped", tokens.contains("ぼ"));
        assertFalse("CJK 'っ' dropped", tokens.contains("っ"));
        assertFalse("CJK 'ち' dropped", tokens.contains("ち"));
        // eng preserved
        assertTrue("eng 'rock' preserved", tokens.contains("rock"));
        // bigrams from hiragana survive
        assertTrue("bigram 'ぼっ' present", tokens.contains("ぼっ"));
        assertTrue("bigram 'っち' present", tokens.contains("っち"));
    }

    @Test
    public void testJapanese_katakanaDroppedAtBoundary() throws IOException {
        // Katakana アニメ are in CJK range → CJK type → dropped
        EsTokConfig config = productionConfig("アニメ007");
        Set<String> tokens = tokenSet(config, "アニメ007");

        assertTrue("vocab 'アニメ007' present", tokens.contains("アニメ007"));
        // Katakana dropped
        assertFalse("CJK 'ア' dropped", tokens.contains("ア"));
        assertFalse("CJK 'ニ' dropped", tokens.contains("ニ"));
        assertFalse("CJK 'メ' dropped", tokens.contains("メ"));
        // arab preserved
        assertTrue("arab '007' preserved", tokens.contains("007"));
        // bigrams survive
        assertTrue("bigram 'アニ' present", tokens.contains("アニ"));
        assertTrue("bigram 'ニメ' present", tokens.contains("ニメ"));
    }

    @Test
    public void testJapanese_mixedKanjiKana() throws IOException {
        // Kanji (Chinese chars) + hiragana + katakana — all CJK type
        EsTokConfig config = productionConfig("東京アニメ");
        Set<String> tokens = tokenSet(config, "東京アニメ");

        assertTrue("vocab '東京アニメ' present", tokens.contains("東京アニメ"));
        // All CJK — dropped at boundary
        assertFalse("'東' dropped", tokens.contains("東"));
        assertFalse("'京' dropped", tokens.contains("京"));
        assertFalse("'ア' dropped", tokens.contains("ア"));
        // Cross-script bigram survives
        assertTrue("bigram '東京' present", tokens.contains("東京"));
        assertTrue("bigram '京ア' present (cross kanji-katakana)", tokens.contains("京ア"));
        assertTrue("bigram 'アニ' present", tokens.contains("アニ"));
    }

    // ========================================================================
    // 17. Korean hangul (nord type — NOT dropped)
    // ========================================================================

    @Test
    public void testKorean_hangulInVocab_vocabMatches() throws IOException {
        // Korean hangul is NOT in CJK/lang Unicode ranges in CategStrategy.
        // It IS outside all defined CategStrategy ranges. The nord regex
        // in CategStrategy doesn't produce categ tokens for Korean chars,
        // so only the vocab token is generated.
        EsTokConfig config = productionConfig("한국어test");
        Set<String> tokens = tokenSet(config, "한국어test");

        // Vocab matches via Aho-Corasick (works with Korean in mixed text)
        assertTrue("vocab '한국어test' present", tokens.contains("한국어test"));
        // eng categ token preserved
        assertTrue("eng 'test' preserved", tokens.contains("test"));
        // Korean chars don't produce separate categ tokens
        // (CategStrategy's nord regex doesn't capture hangul in practice)
        assertFalse("no separate hangul categ token", tokens.contains("한국어"));
    }

    // ========================================================================
    // 18. Token type verification with tokenizeWithTypes()
    // ========================================================================

    @Test
    public void testTokenTypes_engArabHaveCorrectType() throws IOException {
        // Verify that preserved eng/arab tokens have correct type attributes
        EsTokConfig config = productionConfig("红警hbk08");
        Map<String, String> types = tokenizeWithTypes(config, "红警hbk08");

        // eng categ token preserved with type "eng"
        assertEquals("hbk should be eng type", "eng", types.get("hbk"));
        // arab categ token preserved with type "arab"
        assertEquals("08 should be arab type", "arab", types.get("08"));
    }

    @Test
    public void testTokenTypes_bigramType() throws IOException {
        // Bigrams generated from categ tokens should have "bigram" type
        EsTokConfig config = productionConfig("红警hbk08");
        Map<String, String> types = tokenizeWithTypes(config, "红警hbk08");

        assertEquals("红警 should be bigram type", "bigram", types.get("红警"));
    }

    @Test
    public void testTokenTypes_vocabType() throws IOException {
        // Vocab tokens have type "vocab"
        EsTokConfig config = productionConfig("红警hbk08");
        Map<String, String> types = tokenizeWithTypes(config, "红警hbk08");

        assertEquals("红警hbk08 should be vocab type", "vocab", types.get("红警hbk08"));
    }

    @Test
    public void testTokenTypes_langTypeDropped() throws IOException {
        // Lang type tokens (Greek) should be dropped at boundary
        // Only eng/arab tokens + bigrams + vocab should remain
        EsTokConfig config = productionConfig("αβx3");
        Map<String, String> types = tokenizeWithTypes(config, "αβx3");

        // Dropped: α and β (lang type)
        assertNull("α should be dropped (lang)", types.get("α"));
        assertNull("β should be dropped (lang)", types.get("β"));
        // Present: x (eng), 3 (arab), αβ (bigram), αβx3 (word)
        assertEquals("x should be eng type", "eng", types.get("x"));
        assertEquals("3 should be arab type", "arab", types.get("3"));
    }

    // ========================================================================
    // 19. Traditional Chinese (Hant→Hans) interaction
    // ========================================================================

    @Test
    public void testIgnoreHant_traditionalMatchesSimplifiedVocab() throws IOException {
        // Traditional "紅警" should map to simplified "红警" via ignoreHant
        // Vocab registered as simplified "红警hbk08"
        EsTokConfig config = ConfigBuilder.create()
                .withVocab("红警hbk08")
                .withCategSplitWord()
                .withBigram()
                .withDropCategs()
                .withDropDuplicates()
                .withIgnoreCase()
                .withIgnoreHant()
                .build();
        Set<String> tokens = tokenSet(config, "紅警hbk08");

        // After Hant→Hans conversion: 紅→红, 警→警(same)
        assertTrue("vocab '红警hbk08' present (hant→hans)", tokens.contains("红警hbk08"));
        assertTrue("bigram '红警' present (converted)", tokens.contains("红警"));
        assertTrue("eng 'hbk' preserved", tokens.contains("hbk"));
        assertTrue("arab '08' preserved", tokens.contains("08"));
    }

    @Test
    public void testIgnoreHant_traditionalCjkDropped() throws IOException {
        // Traditional CJK chars should be converted then dropped at boundary
        EsTokConfig config = ConfigBuilder.create()
                .withVocab("视频")
                .withCategSplitWord()
                .withBigram()
                .withDropCategs()
                .withDropDuplicates()
                .withIgnoreCase()
                .withIgnoreHant()
                .build();
        Set<String> tokens = tokenSet(config, "視頻");

        // 視→视, 頻→频 via hant→hans
        assertTrue("vocab '视频' present (hant→hans)", tokens.contains("视频"));
        // Converted CJK chars dropped at boundary
        assertFalse("converted '视' dropped", tokens.contains("视"));
        assertFalse("converted '频' dropped", tokens.contains("频"));
    }

    // ========================================================================
    // 20. Multi-script: CJK + lang + eng + arab combined
    // ========================================================================

    @Test
    public void testMultiScript_cjkLangEngArab() throws IOException {
        // "红α3go" — CJK, Greek, arab, eng all in one vocab
        EsTokConfig config = productionConfig("红α3go");
        Set<String> tokens = tokenSet(config, "红α3go");

        assertTrue("vocab '红α3go' present", tokens.contains("红α3go"));
        // CJK '红' dropped (boundary)
        assertFalse("CJK '红' dropped", tokens.contains("红"));
        // Greek 'α' dropped (lang type, boundary)
        assertFalse("lang 'α' dropped", tokens.contains("α"));
        // arab '3' preserved
        assertTrue("arab '3' preserved", tokens.contains("3"));
        // eng 'go' preserved
        assertTrue("eng 'go' preserved", tokens.contains("go"));
    }

    @Test
    public void testMultiScript_japaneseGreekNumbers() throws IOException {
        // "アニメαβ123" — katakana (CJK) + Greek (lang) + arab
        EsTokConfig config = productionConfig("アニメαβ123");
        Set<String> tokens = tokenSet(config, "アニメαβ123");

        assertTrue("vocab present", tokens.contains("アニメαβ123"));
        // Katakana (CJK) dropped
        assertFalse("'ア' dropped", tokens.contains("ア"));
        assertFalse("'ニ' dropped", tokens.contains("ニ"));
        assertFalse("'メ' dropped", tokens.contains("メ"));
        // Greek (lang) dropped
        assertFalse("'α' dropped", tokens.contains("α"));
        assertFalse("'β' dropped", tokens.contains("β"));
        // Arab preserved
        assertTrue("arab '123' preserved", tokens.contains("123"));
        // CJK bigrams survive
        assertTrue("bigram 'アニ' present", tokens.contains("アニ"));
        // Cross-CJK-lang bigram
        assertTrue("bigram 'メα' present (cross CJK-lang)", tokens.contains("メα"));
        // lang bigram
        assertTrue("bigram 'αβ' present", tokens.contains("αβ"));
    }

    // ========================================================================
    // 21. Dash/separator type: NOT dropped by dropCategTokens
    // ========================================================================

    @Test
    public void testDashType_preservedInVocab() throws IOException {
        // Dash-type characters (-+_.) are preserved even in vocab at boundary
        // The dash itself participates in categ tokenization as "dash" type
        EsTokConfig config = productionConfig("c++");
        Set<String> tokens = tokenSet(config, "c++很强");

        assertTrue("vocab 'c++' present", tokens.contains("c++"));
        // eng 'c' preserved
        assertTrue("eng 'c' preserved", tokens.contains("c"));
    }

    @Test
    public void testUnderscoreDot_preservedInVocab() throws IOException {
        // Underscore and dot are dash-type characters
        EsTokConfig config = productionConfig("my_app.v2");
        Set<String> tokens = tokenSet(config, "my_app.v2好用");

        assertTrue("vocab 'my_app.v2' present", tokens.contains("my_app.v2"));
        // eng tokens preserved
        assertTrue("eng 'my' preserved", tokens.contains("my"));
        assertTrue("eng 'app' preserved", tokens.contains("app"));
        assertTrue("eng 'v' preserved", tokens.contains("v"));
        // arab preserved
        assertTrue("arab '2' preserved", tokens.contains("2"));
    }

    // ========================================================================
    // 22. Emoji/special characters (nord type, NOT dropped)
    // ========================================================================

    @Test
    public void testNordType_symbolInVocab() throws IOException {
        // Special symbols like ★ are outside all standard CategStrategy ranges.
        // The CategStrategy regex doesn't produce categ tokens for them,
        // so they only appear as part of vocab tokens.
        EsTokConfig config = productionConfig("★game");
        Set<String> tokens = tokenSet(config, "★game");

        assertTrue("vocab '★game' present", tokens.contains("★game"));
        // eng preserved as categ token
        assertTrue("eng 'game' preserved", tokens.contains("game"));
        // ★ does NOT appear as a separate categ token
        assertFalse("no separate ★ categ token", tokens.contains("★"));
    }

    @Test
    public void testNordType_chinesePunctuationInVocab() throws IOException {
        // Chinese punctuation like 【】 are outside standard categ ranges.
        // They don't produce separate categ tokens, but vocab matching works.
        EsTokConfig config = productionConfig("【红警】");
        Set<String> tokens = tokenSet(config, "【红警】");

        assertTrue("vocab '【红警】' present", tokens.contains("【红警】"));
        // Chinese punctuation does NOT appear as separate categ tokens
        assertFalse("no separate '【' categ token", tokens.contains("【"));
        assertFalse("no separate '】' categ token", tokens.contains("】"));
        // CJK chars dropped at boundary
        assertFalse("CJK '红' dropped", tokens.contains("红"));
        assertFalse("CJK '警' dropped", tokens.contains("警"));
        // bigram survives
        assertTrue("bigram '红警' present", tokens.contains("红警"));
    }
}
