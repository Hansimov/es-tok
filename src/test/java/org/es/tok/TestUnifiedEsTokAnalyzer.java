package org.es.tok;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.es.tok.analysis.EsTokAnalyzer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class TestUnifiedEsTokAnalyzer {

    public static void main(String[] args) throws IOException {
        System.out.println("=== Unified ES-TOK Analyzer Test ===\n");

        String text = "你好我的世界123Test-end!@#";
        List<String> vocabs = Arrays.asList("你好", "我的世界", "test", "end");

        // Test 1: Vocabulary only
        System.out.println("=== Test 1: Vocabulary Only ===");
        testAnalyzer(text, true, false, vocabs, false);

        // Test 2: Categorization only
        System.out.println("=== Test 2: Categorization Only ===");
        testAnalyzer(text, false, true, vocabs, false);

        // Test 3: Both enabled
        System.out.println("=== Test 3: Both Vocabulary and Categorization ===");
        testAnalyzer(text, true, true, vocabs, false);

        // Test 4: Case sensitivity
        System.out.println("=== Test 4: Case Sensitive Vocabulary ===");
        testAnalyzer(text, true, false, vocabs, true);
    }

    private static void testAnalyzer(String text, boolean enableVocab, boolean enableCateg,
            List<String> vocabs, boolean caseSensitive) throws IOException {

        System.out.println("Input: " + text);
        System.out.println("Config: enableVocab=" + enableVocab + ", enableCateg=" + enableCateg +
                ", caseSensitive=" + caseSensitive);

        try (EsTokAnalyzer analyzer = new EsTokAnalyzer(enableVocab, enableCateg, vocabs, caseSensitive)) {
            TokenStream tokenStream = analyzer.tokenStream("field", text);

            CharTermAttribute termAtt = tokenStream.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAtt = tokenStream.addAttribute(OffsetAttribute.class);
            TypeAttribute typeAtt = tokenStream.addAttribute(TypeAttribute.class);

            tokenStream.reset();

            System.out.println("Tokens:");
            int tokenCount = 0;
            while (tokenStream.incrementToken()) {
                tokenCount++;
                System.out.printf("  [%d] '%s' [%d-%d] type='%s'%n",
                        tokenCount,
                        termAtt.toString(),
                        offsetAtt.startOffset(),
                        offsetAtt.endOffset(),
                        typeAtt.type());
            }

            if (tokenCount == 0) {
                System.out.println("  No tokens found.");
            }

            tokenStream.end();
        }
        System.out.println();
    }
}