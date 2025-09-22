package org.es.tok;

import org.es.tok.analysis.EsTokAnalyzer;
import org.es.tok.config.EsTokConfig;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DropCategsTest {
    public static void main(String[] args) throws IOException {
        System.out.println("=== Testing drop_categs functionality ===");

        String text = "游戏我的世界的开发者是谁";
        List<String> vocabs = Arrays.asList("游戏", "我的", "我的世界", "世界", "世界的", "界的", "开发", "开发者", "者是", "是谁");

        System.out.println("Input text: " + text);
        System.out.println("Vocabs: " + vocabs);

        // Test without drop_categs
        System.out.println("\n--- Without drop_categs ---");
        EsTokConfig configWithoutDrop = ConfigBuilder.create()
                .withVocab(vocabs)
                .withCateg()
                .withCategSplitWord()
                .withDropDuplicates()
                .build();

        testAnalyzer(configWithoutDrop, text);

        // Test with drop_categs
        System.out.println("\n--- With drop_categs ---");
        EsTokConfig configWithDrop = ConfigBuilder.create()
                .withVocab(vocabs)
                .withCateg()
                .withCategSplitWord()
                .withDropDuplicates()
                .withDropCategs()
                .build();

        testAnalyzer(configWithDrop, text);
    }

    private static void testAnalyzer(EsTokConfig config, String text) throws IOException {
        EsTokAnalyzer analyzer = new EsTokAnalyzer(config);

        try (TokenStream stream = analyzer.tokenStream("test", new StringReader(text))) {
            CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAtt = stream.addAttribute(OffsetAttribute.class);
            TypeAttribute typeAtt = stream.addAttribute(TypeAttribute.class);

            stream.reset();

            List<TokenInfo> tokens = new ArrayList<>();
            while (stream.incrementToken()) {
                tokens.add(new TokenInfo(
                        termAtt.toString(),
                        offsetAtt.startOffset(),
                        offsetAtt.endOffset(),
                        typeAtt.type()));
            }

            stream.end();

            // Print tokens grouped by type
            System.out.println("Categ tokens:");
            tokens.stream()
                    .filter(t -> "cjk".equals(t.type))
                    .forEach(t -> System.out.println("  " + t));

            System.out.println("Vocab tokens:");
            tokens.stream()
                    .filter(t -> "vocab".equals(t.type))
                    .forEach(t -> System.out.println("  " + t));

            System.out.println("Total tokens: " + tokens.size());
        }

        analyzer.close();
    }

    private static class TokenInfo {
        final String token;
        final int start;
        final int end;
        final String type;

        TokenInfo(String token, int start, int end, String type) {
            this.token = token;
            this.start = start;
            this.end = end;
            this.type = type;
        }

        @Override
        public String toString() {
            return String.format("'%s' [%d-%d]", token, start, end);
        }
    }
}