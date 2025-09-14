package org.es.tok;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.es.tok.tokenize.GroupAttribute;
import org.es.tok.analysis.EsTokAnalyzer;
import org.es.tok.config.EsTokConfig;

import java.io.IOException;

/**
 * Utility class for testing ES-TOK analyzer
 */
public class TestUtils {

    /**
     * Test analyzer with given configuration and print results
     */
    public static void testAndPrintResults(String testName, String text, EsTokConfig config) throws IOException {
        System.out.println("=== " + testName + " ===");
        System.out.println("Input: " + text);
        System.out.println("Config: " + config);

        try (EsTokAnalyzer analyzer = new EsTokAnalyzer(config)) {
            TokenStream tokenStream = analyzer.tokenStream("field", text);

            CharTermAttribute termAtt = tokenStream.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAtt = tokenStream.addAttribute(OffsetAttribute.class);
            TypeAttribute typeAtt = tokenStream.addAttribute(TypeAttribute.class);
            GroupAttribute groupAtt = tokenStream.addAttribute(GroupAttribute.class);

            tokenStream.reset();

            System.out.println("Tokens:");
            int tokenIdx = 0;
            while (tokenStream.incrementToken()) {
                tokenIdx++;
                System.out.printf("  [%2d] [%2d-%2d] <%6s> (%5s): %s%n",
                        tokenIdx,
                        offsetAtt.startOffset(),
                        offsetAtt.endOffset(),
                        typeAtt.type(),
                        groupAtt.group(),
                        termAtt.toString());
            }

            if (tokenIdx == 0) {
                System.out.println("  No tokens found.");
            }

            tokenStream.end();
        }
        System.out.println();
    }

    /**
     * Test analyzer with minimal output
     */
    public static void testQuietly(String text, EsTokConfig config) throws IOException {
        try (EsTokAnalyzer analyzer = new EsTokAnalyzer(config)) {
            TokenStream tokenStream = analyzer.tokenStream("field", text);
            tokenStream.reset();

            while (tokenStream.incrementToken()) {
                // Process tokens silently for performance testing
            }

            tokenStream.end();
        }
    }
}
