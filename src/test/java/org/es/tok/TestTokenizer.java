package org.es.tok;

import org.es.tok.lucene.AhoCorasickTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;

public class TestTokenizer {

    public static void main(String[] args) throws IOException {
        // Test vocabulary
        List<String> vocabulary = Arrays.asList(
                "machine learning",
                "deep learning",
                "learning with",
                "neural network",
                "artificial intelligence",
                "elasticsearch",
                "lucene",
                "java",
                "algorithm");

        // Test texts
        String[] testTexts = {
                "I love machine learning and deep learning with neural networks",
                "Elasticsearch uses Lucene for full-text search and machine learning capabilities",
                "Java is great for building machine learning algorithms",
                "Deep learning neural network models are powerful"
        };

        System.out.println("=== Aho-Corasick Tokenizer Test ===\n");

        // Test case sensitive
        System.out.println("--- ignore case ---");
        testTokenizer(vocabulary, testTexts, true);
        System.out.println("\n--- not ignore case ---");
        testTokenizer(vocabulary, testTexts, false);
    }

    private static void testTokenizer(List<String> vocabulary, String[] testTexts, boolean caseSensitive)
            throws IOException {
        System.out.println("* vocabs: " + vocabulary);
        System.out.println("* ignore_case: " + !caseSensitive);
        System.out.println();

        for (int i = 0; i < testTexts.length; i++) {
            String text = testTexts[i];
            System.out.println("> [" + (i + 1) + "]: \"" + text + "\"");

            AhoCorasickTokenizer tokenizer = new AhoCorasickTokenizer(vocabulary, caseSensitive);
            tokenizer.setReader(new StringReader(text));

            CharTermAttribute termAtt = tokenizer.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAtt = tokenizer.addAttribute(OffsetAttribute.class);
            PositionIncrementAttribute posIncrAtt = tokenizer.addAttribute(PositionIncrementAttribute.class);

            tokenizer.reset();

            int tokenCount = 0;
            while (tokenizer.incrementToken()) {
                tokenCount++;
                System.out.printf("  * (%02d-%02d) '%s' %n",
                        offsetAtt.startOffset(),
                        offsetAtt.endOffset(),
                        termAtt.toString());
            }

            if (tokenCount == 0) {
                System.out.println("  No tokens found.");
            }

            tokenizer.close();
            System.out.println();
        }
    }
}