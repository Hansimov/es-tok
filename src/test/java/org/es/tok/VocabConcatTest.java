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
 * Tests for vocab tokens that require concatenation of separators.
 */
public class VocabConcatTest {

    public static void main(String[] args) throws IOException {
        System.out.println("=== Vocab Concat ES-TOK Analyzer Tests ===\n");

        testWhitespaceConcat();
        testDashAndMaskConcat();

        System.out.println("All vocab concat tests passed!\n");
    }

    private static void testWhitespaceConcat() throws IOException {
        String text = "deep learning";

        EsTokConfig config = ConfigBuilder.create()
                .withVocab("deep learning")
                .build();

        List<TokenInfo> tokens = analyze(text, config);

        int start = 0;
        int baseEnd = start + "deep learning".length();
        assertHasToken(tokens, "deep learning", start, baseEnd, "vocab", "vocab");
        assertHasToken(tokens, "deeplearning", start, baseEnd, "vocab_concat", "vocab");

        System.out.println("Whitespace concat test passed.");
    }

    private static void testDashAndMaskConcat() throws IOException {
        String text = "t-ara mask▂value";

        EsTokConfig config = ConfigBuilder.create()
                .withVocab("t-ara", "mask▂value")
                .build();

        List<TokenInfo> tokens = analyze(text, config);

        assertHasToken(tokens, "t-ara", 0, 0 + "t-ara".length(), "vocab", "vocab");
        assertHasToken(tokens, "tara", 0, 0 + "t-ara".length(), "vocab_concat", "vocab");
        assertHasToken(tokens, "mask▂value", 6, 6 + "mask▂value".length(), "vocab", "vocab");
        assertHasToken(tokens, "maskvalue", 6, 6 + "mask▂value".length(), "vocab_concat", "vocab");

        System.out.println("Dash and mask concat test passed.");
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
