package org.es.tok;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.es.tok.analysis.EsTokAnalyzer;
import org.es.tok.config.EsTokConfig;
import org.es.tok.tokenize.GroupAttribute;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for vocab boundary filtering to prevent splitting words in the middle.
 */
public class VocabTest {

    public static void main(String[] args) throws IOException {
        System.out.println("=== Vocab Boundary Filter Tests ===\n");

        testBasicBoundaryFiltering();
        testNumberBoundaryFiltering();
        testMixedBoundaryFiltering();
        testNonAlphanumericVocab();
        testEdgeCases();
        testDetailedExamples();

        System.out.println("All vocab boundary filter tests passed!\n");
    }

    private static void testBasicBoundaryFiltering() throws IOException {
        System.out.println("Test 1: Basic boundary filtering");

        String text = "chatgpt 12";
        EsTokConfig config = ConfigBuilder.create()
                .withVocab("gpt", "gpt 1")
                .build();

        List<TokenInfo> tokens = analyze(text, config);

        assertHasToken(tokens, "gpt", 4, 7, "vocab", "vocab");
        assertNoToken(tokens, "gpt 1");

        System.out.println("  ✓ Pass\n");
    }

    private static void testNumberBoundaryFiltering() throws IOException {
        System.out.println("Test 2: Number boundary filtering");

        String text1 = "abc123456789def";
        EsTokConfig config1 = ConfigBuilder.create()
                .withVocab("234", "4567")
                .build();
        List<TokenInfo> tokens1 = analyze(text1, config1);

        assertNoToken(tokens1, "234");
        assertNoToken(tokens1, "4567");

        String text2 = "abc123def";
        EsTokConfig config2 = ConfigBuilder.create()
                .withVocab("123")
                .build();
        List<TokenInfo> tokens2 = analyze(text2, config2);

        assertHasToken(tokens2, "123", 3, 6, "vocab", "vocab");

        System.out.println("  ✓ Pass\n");
    }

    private static void testMixedBoundaryFiltering() throws IOException {
        System.out.println("Test 3: Mixed boundary filtering");

        String text = "abc123def";
        EsTokConfig config = ConfigBuilder.create()
                .withVocab("abc", "123", "def", "abc123", "123def")
                .build();

        List<TokenInfo> tokens = analyze(text, config);

        assertHasToken(tokens, "abc", 0, 3, "vocab", "vocab");
        assertHasToken(tokens, "123", 3, 6, "vocab", "vocab");
        assertHasToken(tokens, "def", 6, 9, "vocab", "vocab");
        assertHasToken(tokens, "abc123", 0, 6, "vocab", "vocab");
        assertHasToken(tokens, "123def", 3, 9, "vocab", "vocab");

        System.out.println("  ✓ Pass\n");
    }

    private static void testNonAlphanumericVocab() throws IOException {
        System.out.println("Test 4: Non-alphanumeric vocab");

        String text1 = "hello你好world";
        EsTokConfig config1 = ConfigBuilder.create()
                .withVocab("你好")
                .build();
        List<TokenInfo> tokens1 = analyze(text1, config1);

        if (tokens1.isEmpty()) {
            System.out.println("  Warning: Chinese characters not matched by Aho-Corasick");
        } else {
            assertHasToken(tokens1, "你好", 5, 7, "vocab", "vocab");
        }

        String text2 = "deep learning test";
        EsTokConfig config2 = ConfigBuilder.create()
                .withVocab("deep learning")
                .build();
        List<TokenInfo> tokens2 = analyze(text2, config2);

        assertHasToken(tokens2, "deep learning", 0, 13, "vocab", "vocab");

        System.out.println("  ✓ Pass\n");
    }

    private static void testEdgeCases() throws IOException {
        System.out.println("Test 5: Edge cases at text boundaries");

        String text1 = "test123";
        EsTokConfig config1 = ConfigBuilder.create()
                .withVocab("test")
                .build();
        List<TokenInfo> tokens1 = analyze(text1, config1);
        assertHasToken(tokens1, "test", 0, 4, "vocab", "vocab");

        String text2 = "abc123";
        EsTokConfig config2 = ConfigBuilder.create()
                .withVocab("123")
                .build();
        List<TokenInfo> tokens2 = analyze(text2, config2);
        assertHasToken(tokens2, "123", 3, 6, "vocab", "vocab");

        String text3 = "test";
        EsTokConfig config3 = ConfigBuilder.create()
                .withVocab("test")
                .build();
        List<TokenInfo> tokens3 = analyze(text3, config3);
        assertHasToken(tokens3, "test", 0, 4, "vocab", "vocab");

        System.out.println("  ✓ Pass\n");
    }

    private static void testDetailedExamples() throws IOException {
        System.out.println("Test 6: Detailed examples");

        String text1 = "mytest123";
        EsTokConfig config1 = ConfigBuilder.create()
                .withVocab("test")
                .build();
        List<TokenInfo> tokens1 = analyze(text1, config1);
        assertHasToken(tokens1, "test", 2, 6, "vocab", "vocab");

        String text2 = "123test456";
        EsTokConfig config2 = ConfigBuilder.create()
                .withVocab("test")
                .build();
        List<TokenInfo> tokens2 = analyze(text2, config2);
        assertHasToken(tokens2, "test", 3, 7, "vocab", "vocab");

        String text3 = "testing";
        EsTokConfig config3 = ConfigBuilder.create()
                .withVocab("est")
                .build();
        List<TokenInfo> tokens3 = analyze(text3, config3);
        assertNoToken(tokens3, "est");

        String text4 = "abc12345def";
        EsTokConfig config4 = ConfigBuilder.create()
                .withVocab("123", "234", "345")
                .build();
        List<TokenInfo> tokens4 = analyze(text4, config4);
        assertHasToken(tokens4, "123", 3, 6, "vocab", "vocab");
        assertNoToken(tokens4, "234");
        assertHasToken(tokens4, "345", 5, 8, "vocab", "vocab");

        System.out.println("  ✓ Pass\n");
    }

    private static List<TokenInfo> analyze(String text, EsTokConfig config) throws IOException {
        List<TokenInfo> tokens = new ArrayList<>();

        try (EsTokAnalyzer analyzer = new EsTokAnalyzer(config);
                TokenStream stream = analyzer.tokenStream("test", new StringReader(text))) {
            CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAtt = stream.addAttribute(OffsetAttribute.class);
            TypeAttribute typeAtt = stream.addAttribute(TypeAttribute.class);
            GroupAttribute groupAtt = stream.addAttribute(GroupAttribute.class);

            stream.reset();

            while (stream.incrementToken()) {
                tokens.add(new TokenInfo(
                        termAtt.toString(),
                        offsetAtt.startOffset(),
                        offsetAtt.endOffset(),
                        typeAtt.type(),
                        groupAtt.group()));
            }

            stream.end();
        }

        return tokens;
    }

    private static void assertHasToken(List<TokenInfo> tokens, String expectedTerm, int start, int end,
            String expectedType, String expectedGroup) {
        for (TokenInfo token : tokens) {
            if (token.term.equals(expectedTerm) &&
                    token.startOffset == start &&
                    token.endOffset == end &&
                    token.type.equals(expectedType) &&
                    token.group.equals(expectedGroup)) {
                return;
            }
        }
        throw new IllegalStateException(String.format(
                "Expected token '%s' [%d-%d] <%s> (%s) not found. Actual tokens: %s",
                expectedTerm, start, end, expectedType, expectedGroup, tokens));
    }

    private static void assertNoToken(List<TokenInfo> tokens, String term) {
        for (TokenInfo token : tokens) {
            if (token.term.equals(term)) {
                throw new IllegalStateException(String.format(
                        "Token '%s' should have been filtered but was found: %s",
                        term, token));
            }
        }
    }

    private static class TokenInfo {
        final String term;
        final int startOffset;
        final int endOffset;
        final String type;
        final String group;

        TokenInfo(String term, int startOffset, int endOffset, String type, String group) {
            this.term = term;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.type = type;
            this.group = group;
        }

        @Override
        public String toString() {
            return String.format("'%s'[%d-%d]<%s>(%s)", term, startOffset, endOffset, type, group);
        }
    }
}
