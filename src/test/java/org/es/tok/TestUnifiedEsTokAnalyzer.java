package org.es.tok;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.es.tok.analysis.EsTokAnalyzer;
import org.es.tok.file.VocabLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class TestUnifiedEsTokAnalyzer {

    public static void main(String[] args) throws IOException {
        System.out.println("=== Unified ES-TOK Analyzer Test ===\n");

        String text = "123你好我的世界⻊⻌⽱⿑ Hello World Test-end GTA5 Chat GPT!@#ЗИЙЛМНЙατυφχψω幩槪𠆆𠇜";
        List<String> vocabs = Arrays.asList("你好", "你好我的", "我的世界", "hello world", "test", "end", "МНЙ", "υφχ");

        // Test 1: use_vocab
        System.out.println("=== Test 1: [vocab] ===");
        testAnalyzer(text, true, false, vocabs, false, false);

        // Test 2: use_categ + split_word
        System.out.println("=== Test 2: [categ, split_word] ===");
        testAnalyzer(text, false, true, vocabs, false, true);

        // Test 3: use_vocab + use_categ
        System.out.println("=== Test 3: [vocab, categ] ===");
        testAnalyzer(text, true, true, vocabs, false, false);

        // Test 4: use_vocab + use_categ
        System.out.println("=== Test 4: [vocab, categ, split_word] ===");
        testAnalyzer(text, true, true, vocabs, false, true);

        // Test 5: use_vocab + ignore_case
        System.out.println("=== Test 5: [vocab, ignore_case] ===");
        testAnalyzer(text, true, false, vocabs, true, false);

        // Test 6: use_vocab + vocab file
        System.out.println("=== Test 6: [vocab, ignore_case, vocab file] ===");
        String vocabFile = "/home/asimov/repos/bili-search-algo/models/sentencepiece/checkpoints/sp_merged.txt";
        Path vocabFilePath = Paths.get(vocabFile);
        if (Files.exists(vocabFilePath)) {
            List<String> fileVocabs = VocabLoader.loadVocabsFromFilePath(vocabFilePath);
            testAnalyzer(text, true, false, fileVocabs, true, false);
        } else {
            System.out.println("Vocab file not found: " + vocabFile);
        }

    }

    private static void testAnalyzer(
            String text, boolean useVocab, boolean useCateg, List<String> vocabs, boolean ignoreCase, boolean splitWord)
            throws IOException {

        System.out.println("Input:  " + text);
        System.out.println("Config: [useVocab=%b, useCateg=%b, ignoreCase=%b, splitWord=%b)".formatted(
                useVocab, useCateg, ignoreCase, splitWord));

        try (EsTokAnalyzer analyzer = new EsTokAnalyzer(useVocab, useCateg, vocabs, ignoreCase, splitWord)) {
            TokenStream tokenStream = analyzer.tokenStream("field", text);

            CharTermAttribute termAtt = tokenStream.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAtt = tokenStream.addAttribute(OffsetAttribute.class);
            TypeAttribute typeAtt = tokenStream.addAttribute(TypeAttribute.class);

            tokenStream.reset();

            System.out.println("Tokens:");
            int tokenIdx = 0;
            while (tokenStream.incrementToken()) {
                tokenIdx++;
                System.out.printf("  [%2d] [%2d-%2d] <%5s>: %s%n",
                        tokenIdx,
                        offsetAtt.startOffset(),
                        offsetAtt.endOffset(),
                        typeAtt.type(),
                        termAtt.toString());
            }

            if (tokenIdx == 0) {
                System.out.println("  No tokens found.");
            }

            tokenStream.end();
        }
        System.out.println();
    }
}