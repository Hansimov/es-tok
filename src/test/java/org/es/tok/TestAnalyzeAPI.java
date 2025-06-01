package org.es.tok;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.es.tok.analysis.EsTokAnalyzer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class TestAnalyzeAPI {

    public static void main(String[] args) throws IOException {
        String text = "你好我的世界是一款沙盒游戏，我的游戏库里必须有它";
        List<String> vocabs = Arrays.asList("你好", "我的", "我的世界", "沙盒游戏", "世界", "你好世界", "游戏库", "库里");

        System.out.println("=== ES-TOK Analyzer API Test ===\n");
        System.out.println("> Text: " + text);
        System.out.println("  * Vocabs: " + vocabs);

        testAnalyzer(text, vocabs, false);
    }

    private static void testAnalyzer(String text, List<String> vocabs, boolean caseSensitive) throws IOException {
        EsTokAnalyzer analyzer = new EsTokAnalyzer(vocabs, caseSensitive);

        try (TokenStream tokenStream = analyzer.tokenStream("field", text)) {
            CharTermAttribute termAtt = tokenStream.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAtt = tokenStream.addAttribute(OffsetAttribute.class);

            tokenStream.reset();

            int tokenCount = 0;
            while (tokenStream.incrementToken()) {
                tokenCount++;
                System.out.printf("  * [%d] (%02d-%02d): '%s'%n",
                        tokenCount,
                        offsetAtt.startOffset(),
                        offsetAtt.endOffset(),
                        termAtt.toString());
            }

            if (tokenCount == 0) {
                System.out.println("  No tokens found.");
            }

            tokenStream.end();
        }

        analyzer.close();
    }
}