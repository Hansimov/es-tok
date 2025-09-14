package org.es.tok;

import org.es.tok.vocab.VocabFileLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Vocab file loading tests for ES-TOK analyzer
 */
public class VocabFileTest {

    public static void main(String[] args) throws IOException {
        System.out.println("=== Vocab File ES-TOK Analyzer Tests ===\n");

        String text = "123你好我的世界⻊⻌⽱⿑ Hello World Test-end GTA5 Chat GPT!@#ЗИЙЛМНЙατυφχψω幩槪𠆆𠇜";

        // Test with vocab file if it exists
        String vocabFile = "/home/asimov/repos/bili-search-algo/models/sentencepiece/checkpoints/sp_merged.txt";
        Path vocabFilePath = Paths.get(vocabFile);

        if (Files.exists(vocabFilePath)) {
            System.out.println("Loading vocabs from file: " + vocabFile);
            List<String> fileVocabs = VocabFileLoader.loadVocabsFromFilePath(vocabFilePath);
            System.out.println("Loaded " + fileVocabs.size() + " vocabulary terms");

            TestUtils.testAndPrintResults(
                    "Test: Vocab File + Ignore Case",
                    text,
                    ConfigBuilder.create()
                            .withVocab(fileVocabs)
                            .withIgnoreCase()
                            .build());
        } else {
            System.out.println("Vocab file not found: " + vocabFile);
            System.out.println("Skipping vocab file tests");
        }
    }
}
