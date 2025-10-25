package org.es.tok;

import java.io.IOException;

public class TestRunner {

    public static void main(String[] args) throws IOException {
        System.out.println("========================================");
        System.out.println("   ES-TOK Analyzer Test Suite");
        System.out.println("========================================\n");

        if (args.length > 0) {
            String testName = normalizeTestName(args[0]);
            boolean testFound = runTest(testName);
            if (!testFound) {
                System.out.println("Unknown test: " + args[0]);
                printUsage();
            }
            return;
        }

        // Run all tests
        System.out.println("1. Running Basic Tests...");
        BasicAnalyzerTest.main(args);

        System.out.println("2. Running N-gram Tests...");
        NgramAnalyzerTest.main(args);

        System.out.println("3. Running Drop Duplicates Tests...");
        DropDuplicatesTest.main(args);

        System.out.println("4. Running Cogram Tests...");
        CogramTest.main(args);

        System.out.println("5. Running Vocab Boundary Tests...");
        VocabTest.main(args);

        System.out.println("6. Running Vocab File Tests...");
        VocabFileTest.main(args);

        System.out.println("7. Running Vocab Concat Tests...");
        VocabConcatTest.main(args);

        System.out.println("========================================");
        System.out.println("   All tests completed!");
        System.out.println("========================================");
    }

    private static String normalizeTestName(String input) {
        String normalized = input.toLowerCase().replace("_", "");
        return normalized;
    }

    private static boolean runTest(String testName) {
        try {
            switch (testName) {
                case "basicanalyzer":
                case "basic":
                    BasicAnalyzerTest.main(new String[0]);
                    return true;
                case "ngramanalyzer":
                case "ngram":
                    NgramAnalyzerTest.main(new String[0]);
                    return true;
                case "dropduplicates":
                case "duplicates":
                    DropDuplicatesTest.main(new String[0]);
                    return true;
                case "dropvocabs":
                    DropVocabsTest.main(new String[0]);
                    return true;
                case "dropcategs":
                    DropCategsTest.main(new String[0]);
                    return true;
                case "cogram":
                    CogramTest.main(new String[0]);
                    return true;
                case "vocab":
                    VocabTest.main(new String[0]);
                    return true;
                case "vocabfile":
                    VocabFileTest.main(new String[0]);
                    return true;
                case "vocabconcat":
                    VocabConcatTest.main(new String[0]);
                    return true;
                case "performance":
                    PerformanceTest.main(new String[0]);
                    return true;
                case "hanttohans":
                    HantToHansTest.main(new String[0]);
                    return true;
                default:
                    return false;
            }
        } catch (Exception e) {
            System.err.println("Error running test: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static void printUsage() {
        System.out.println("Usage: ./gradlew testRunner --args=<test_name>");
        System.out.println("Available tests:");
        System.out.println("  BasicAnalyzer / basic / basic_analyzer");
        System.out.println("  NgramAnalyzer / ngram / ngram_analyzer");
        System.out.println("  DropDuplicates / duplicates / drop_duplicates");
        System.out.println("  DropVocabs / drop_vocabs");
        System.out.println("  DropCategs / drop_categs");
        System.out.println("  Cogram / cogram");
        System.out.println("  Vocab / vocab");
        System.out.println("  VocabFile / vocab_file");
        System.out.println("  VocabConcat / vocab_concat");
        System.out.println("  Performance / performance");
        System.out.println("  HantToHans / hant_to_hans");
        System.out.println("  (no args) - Run all tests");
    }
}
