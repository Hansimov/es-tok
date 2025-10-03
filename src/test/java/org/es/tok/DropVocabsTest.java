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
import java.util.Arrays;
import java.util.List;

public class DropVocabsTest {
    public static void main(String[] args) throws IOException {
        System.out.println("=== Testing drop_vocabs functionality ===");

        String text = "java script";
        List<String> vocabs = Arrays.asList("a s", "a sc", "java");

        System.out.println("Input text: " + text);
        System.out.println("Vocabs: " + vocabs);

        EsTokConfig configWithoutDrop = ConfigBuilder.create()
                .withVocab(vocabs)
                .withCategSplitWord()
                .withDropDuplicates()
                .withDropCategs()
                .build();

        EsTokConfig configWithDrop = ConfigBuilder.create()
                .withVocab(vocabs)
                .withCategSplitWord()
                .withDropDuplicates()
                .withDropCategs()
                .withDropVocabs()
                .build();

        testAnalyzer("Without drop_vocabs", configWithoutDrop, text);
        testAnalyzer("With drop_vocabs", configWithDrop, text);
    }

    private static List<TokenInfo> testAnalyzer(String label, EsTokConfig config, String text) throws IOException {
        System.out.println("\n--- " + label + " ---");

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

        System.out.println("Non-vocab tokens:");
        tokens.stream()
                .filter(token -> !"vocab".equals(token.group))
                .forEach(token -> System.out.println("  " + token));

        System.out.println("Vocab tokens:");
        tokens.stream()
                .filter(token -> "vocab".equals(token.group))
                .forEach(token -> System.out.println("  " + token));

        System.out.println("Total tokens: " + tokens.size());

        return tokens;
    }

    private static class TokenInfo {
        final String token;
        final int startOffset;
        final int endOffset;
        final String type;
        final String group;

        TokenInfo(String token, int startOffset, int endOffset, String type, String group) {
            this.token = token;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.type = type;
            this.group = group;
        }

        @Override
        public String toString() {
            return String.format("'%s' [%d-%d] <%s> (%s)", token, startOffset, endOffset, type, group);
        }
    }
}
