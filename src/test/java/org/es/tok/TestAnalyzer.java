package org.es.tok;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class TestAnalyzer {

    // Simplified VocabAnalyzer for testing without Elasticsearch dependencies
    static class TestVocabAnalyzer extends Analyzer {
        private final List<String> vocabulary;
        private final boolean caseSensitive;

        public TestVocabAnalyzer(List<String> vocabulary, boolean caseSensitive) {
            this.vocabulary = vocabulary;
            this.caseSensitive = caseSensitive;
        }

        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            var tokenizer = new org.es.tok.lucene.VocabTokenizer(vocabulary, caseSensitive);

            if (!caseSensitive) {
                var lowerCaseFilter = new org.apache.lucene.analysis.LowerCaseFilter(tokenizer);
                return new TokenStreamComponents(tokenizer, lowerCaseFilter);
            }

            return new TokenStreamComponents(tokenizer);
        }
    }

    public static void main(String[] args) throws IOException {
        List<String> vocabulary = Arrays.asList(
                "machine learning",
                "AI",
                "elasticsearch",
                "search engine",
                "big data");

        String[] testTexts = {
                "Machine Learning is part of AI and elasticsearch is a search engine for big data",
                "ELASTICSEARCH and AI work together for machine learning solutions"
        };

        System.out.println("=== Aho-Corasick Analyzer Test ===\n");

        // Test with case sensitive
        System.out.println("--- Analyzer (not ignore case) ---");
        testAnalyzer(vocabulary, testTexts, true);

        System.out.println("\n--- Analyzer (ignore case) ---");
        testAnalyzer(vocabulary, testTexts, false);
    }

    private static void testAnalyzer(List<String> vocabulary, String[] testTexts, boolean caseSensitive)
            throws IOException {
        TestVocabAnalyzer analyzer = new TestVocabAnalyzer(vocabulary, caseSensitive);

        System.out.println("* vocabs: " + vocabulary);
        System.out.println("* ignore_case: " + !caseSensitive);
        System.out.println();

        for (int i = 0; i < testTexts.length; i++) {
            String text = testTexts[i];
            System.out.println("> Test " + (i + 1) + ": \"" + text + "\"");

            try (TokenStream tokenStream = analyzer.tokenStream("field", text)) {
                CharTermAttribute termAtt = tokenStream.addAttribute(CharTermAttribute.class);
                OffsetAttribute offsetAtt = tokenStream.addAttribute(OffsetAttribute.class);

                tokenStream.reset();

                int tokenCount = 0;
                while (tokenStream.incrementToken()) {
                    tokenCount++;
                    System.out.printf("  %d. '%s' [%d-%d]%n",
                            tokenCount,
                            termAtt.toString(),
                            offsetAtt.startOffset(),
                            offsetAtt.endOffset());
                }

                if (tokenCount == 0) {
                    System.out.println("  No tokens found.");
                }

                tokenStream.end();
            }
            System.out.println();
        }

        analyzer.close();
    }
}