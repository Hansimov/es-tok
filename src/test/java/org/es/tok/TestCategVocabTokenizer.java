package org.es.tok;

import org.es.tok.lucene.CategVocabTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestCategVocabTokenizer {

    public static class TokenResult {
        public final String text;
        public final String type;
        public final int startOffset;
        public final int endOffset;

        public TokenResult(String text, String type, int startOffset, int endOffset) {
            this.text = text;
            this.type = type;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        @Override
        public String toString() {
            return String.format("Token{text='%s', type='%s', offset=[%d,%d]}",
                    text, type, startOffset, endOffset);
        }
    }

    public static List<TokenResult> tokenize(String text, List<String> vocabulary,
            boolean caseSensitive, boolean enableVocabularyMatching) {
        List<TokenResult> results = new ArrayList<>();

        try {
            CategVocabTokenizer tokenizer = new CategVocabTokenizer(
                    vocabulary, caseSensitive, enableVocabularyMatching);
            tokenizer.setReader(new StringReader(text));

            CharTermAttribute termAtt = tokenizer.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAtt = tokenizer.addAttribute(OffsetAttribute.class);
            TypeAttribute typeAtt = tokenizer.addAttribute(TypeAttribute.class);

            tokenizer.reset();
            while (tokenizer.incrementToken()) {
                results.add(new TokenResult(
                        termAtt.toString(),
                        typeAtt.type(),
                        offsetAtt.startOffset(),
                        offsetAtt.endOffset()));
            }
            tokenizer.close();
        } catch (Exception e) {
            System.err.println("Error during tokenization: " + e.getMessage());
            e.printStackTrace();
        }

        return results;
    }

    public static void testBasicCategorization() {
        System.out.println("=== Test: Basic Character Categorization ===");

        String text = "Hello123世界-_test.example";
        List<String> emptyVocab = new ArrayList<>();

        List<TokenResult> tokens = tokenize(text, emptyVocab, false, false);

        System.out.println("Input: " + text);
        System.out.println("Tokens (categorization only):");
        for (TokenResult token : tokens) {
            System.out.println("  " + token);
        }
        System.out.println();
    }

    public static void testCJKText() {
        System.out.println("=== Test: CJK Text Processing ===");

        String text = "你好世界123こんにちはカタカナ테스트";
        List<String> emptyVocab = new ArrayList<>();

        List<TokenResult> tokens = tokenize(text, emptyVocab, false, false);

        System.out.println("Input: " + text);
        System.out.println("Tokens:");
        for (TokenResult token : tokens) {
            System.out.println("  " + token);
        }
        System.out.println();
    }

    public static void testMixedContent() {
        System.out.println("=== Test: Mixed Content with Special Characters ===");

        String text = "Email: user@domain.com Phone: +1-234-567-8900 Price: $29.99 ▂▂▂";
        List<String> emptyVocab = new ArrayList<>();

        List<TokenResult> tokens = tokenize(text, emptyVocab, false, false);

        System.out.println("Input: " + text);
        System.out.println("Tokens:");
        for (TokenResult token : tokens) {
            System.out.println("  " + token);
        }
        System.out.println();
    }

    public static void testVocabularyMatching() {
        System.out.println("=== Test: Vocabulary Matching ===");

        String text = "The quick brown fox jumps over the lazy dog. Machine learning is fascinating!";
        List<String> vocabulary = Arrays.asList(
                "quick", "brown", "fox", "machine learning", "learning", "dog");

        List<TokenResult> tokens = tokenize(text, vocabulary, false, true);

        System.out.println("Input: " + text);
        System.out.println("Vocabulary: " + vocabulary);
        System.out.println("Tokens (with vocabulary matching):");
        for (TokenResult token : tokens) {
            System.out.println("  " + token);
        }
        System.out.println();
    }

    public static void testCaseSensitivity() {
        System.out.println("=== Test: Case Sensitivity ===");

        String text = "Java JAVA java Programming";
        List<String> vocabulary = Arrays.asList("Java", "Programming");

        System.out.println("Input: " + text);
        System.out.println("Vocabulary: " + vocabulary);

        // Case sensitive
        System.out.println("\nCase Sensitive:");
        List<TokenResult> caseSensitiveTokens = tokenize(text, vocabulary, true, true);
        for (TokenResult token : caseSensitiveTokens) {
            System.out.println("  " + token);
        }

        // Case insensitive
        System.out.println("\nCase Insensitive:");
        List<TokenResult> caseInsensitiveTokens = tokenize(text, vocabulary, false, true);
        for (TokenResult token : caseInsensitiveTokens) {
            System.out.println("  " + token);
        }
        System.out.println();
    }

    public static void testOverlappingVocabulary() {
        System.out.println("=== Test: Overlapping Vocabulary ===");

        String text = "machine learning algorithm";
        List<String> vocabulary = Arrays.asList(
                "machine", "learning", "machine learning", "algorithm");

        List<TokenResult> tokens = tokenize(text, vocabulary, false, true);

        System.out.println("Input: " + text);
        System.out.println("Vocabulary: " + vocabulary);
        System.out.println("Tokens:");
        for (TokenResult token : tokens) {
            System.out.println("  " + token);
        }
        System.out.println();
    }

    public static void testMultilingualContent() {
        System.out.println("=== Test: Multilingual Content ===");

        String text = "Hello世界Приветnaïve123Bonjour++test--end";
        List<String> vocabulary = Arrays.asList("Hello", "世界", "test");

        List<TokenResult> tokens = tokenize(text, vocabulary, false, true);

        System.out.println("Input: " + text);
        System.out.println("Vocabulary: " + vocabulary);
        System.out.println("Tokens:");
        for (TokenResult token : tokens) {
            System.out.println("  " + token);
        }
        System.out.println();
    }

    public static void testEmptyAndSpecialCases() {
        System.out.println("=== Test: Empty and Special Cases ===");

        // Empty text
        System.out.println("Empty text:");
        List<TokenResult> emptyTokens = tokenize("", Arrays.asList("test"), false, true);
        System.out.println("Tokens: " + emptyTokens.size());

        // Only spaces
        System.out.println("\nOnly spaces:");
        List<TokenResult> spaceTokens = tokenize("   ", Arrays.asList("test"), false, true);
        for (TokenResult token : spaceTokens) {
            System.out.println("  " + token);
        }

        // Only numbers
        System.out.println("\nOnly numbers:");
        List<TokenResult> numberTokens = tokenize("123456789", new ArrayList<>(), false, false);
        for (TokenResult token : numberTokens) {
            System.out.println("  " + token);
        }

        // Only punctuation
        System.out.println("\nOnly punctuation:");
        List<TokenResult> punctTokens = tokenize("!@#$%^&*()", new ArrayList<>(), false, false);
        for (TokenResult token : punctTokens) {
            System.out.println("  " + token);
        }
        System.out.println();
    }

    public static void testCategorizationOnly() {
        System.out.println("=== Test: Categorization Only (No Vocabulary) ===");

        String text = "Test123测试-_+.end!@#";
        List<String> vocabulary = Arrays.asList("Test", "end");

        // With vocabulary matching disabled
        List<TokenResult> categorizationOnly = tokenize(text, vocabulary, false, false);

        System.out.println("Input: " + text);
        System.out.println("Vocabulary: " + vocabulary + " (disabled)");
        System.out.println("Tokens (categorization only):");
        for (TokenResult token : categorizationOnly) {
            System.out.println("  " + token);
        }
        System.out.println();
    }

    public static void testPerformance() {
        System.out.println("=== Test: Performance ===");

        // Create a longer text for performance testing
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("The quick brown fox jumps over the lazy dog. ");
            sb.append("机器学习算法很有趣。");
            sb.append("123-456-789 test@example.com ");
        }
        String longText = sb.toString();

        List<String> vocabulary = Arrays.asList(
                "quick", "brown", "fox", "dog", "machine", "learning",
                "algorithm", "test", "example");

        long startTime = System.currentTimeMillis();
        List<TokenResult> tokens = tokenize(longText, vocabulary, false, true);
        long endTime = System.currentTimeMillis();

        System.out.println("Text length: " + longText.length() + " characters");
        System.out.println("Vocabulary size: " + vocabulary.size());
        System.out.println("Total tokens: " + tokens.size());
        System.out.println("Processing time: " + (endTime - startTime) + "ms");

        // Show token type distribution
        long arabCount = tokens.stream().filter(t -> "arab".equals(t.type)).count();
        long atozCount = tokens.stream().filter(t -> "atoz".equals(t.type)).count();
        long cjkCount = tokens.stream().filter(t -> "cjk".equals(t.type)).count();
        long dashCount = tokens.stream().filter(t -> "dash".equals(t.type)).count();
        long nordCount = tokens.stream().filter(t -> "nord".equals(t.type)).count();
        long vocabCount = tokens.stream().filter(t -> "vocab".equals(t.type)).count();

        System.out.println("Token distribution:");
        System.out.println("  arab: " + arabCount);
        System.out.println("  atoz: " + atozCount);
        System.out.println("  cjk: " + cjkCount);
        System.out.println("  dash: " + dashCount);
        System.out.println("  nord: " + nordCount);
        System.out.println("  vocab: " + vocabCount);
        System.out.println();
    }

    public static void main(String[] args) {
        System.out.println("Testing Categorized Aho-Corasick Tokenizer");
        System.out.println("==========================================");
        System.out.println();

        try {
            testBasicCategorization();
            testCJKText();
            testMixedContent();
            testVocabularyMatching();
            testCaseSensitivity();
            testOverlappingVocabulary();
            testMultilingualContent();
            testEmptyAndSpecialCases();
            testCategorizationOnly();
            testPerformance();

            System.out.println("All tests completed successfully!");

        } catch (Exception e) {
            System.err.println("Test failed with exception: " + e.getMessage());
            e.printStackTrace();
        }
    }
}