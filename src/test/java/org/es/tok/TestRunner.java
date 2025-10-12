package org.es.tok;

import java.io.IOException;

/**
 * Main test runner for all ES-TOK analyzer tests
 */
public class TestRunner {

    public static void main(String[] args) throws IOException {
        System.out.println("========================================");
        System.out.println("   ES-TOK Analyzer Test Suite");
        System.out.println("========================================\n");

        // Check if specific test is requested
        if (args.length > 0) {
            String testType = args[0].toLowerCase();
            switch (testType) {
                case "basic":
                    BasicAnalyzerTest.main(args);
                    return;
                case "ngram":
                    NgramAnalyzerTest.main(args);
                    return;
                case "duplicates":
                    DropDuplicatesTest.main(args);
                    return;
                case "cogram":
                    CogramTest.main(args);
                    return;
                case "vocab":
                    VocabTest.main(args);
                    return;
                case "vocab_file":
                    VocabFileTest.main(args);
                    return;
                case "vocab_concat":
                    VocabConcatTest.main(args);
                    return;
                case "performance":
                    PerformanceTest.main(args);
                    return;
                default:
                    System.out.println("Unknown test type: " + testType);
                    printUsage();
                    return;
            }
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
        System.out.println("   Use './gradlew testPerformance' for performance tests");
        System.out.println("========================================");
    }

    private static void printUsage() {
        System.out.println("Usage: ./gradlew testRunner [test_type]");
        System.out.println("Available test types:");
        System.out.println("  basic       - Basic functionality tests");
        System.out.println("  ngram       - N-gram specific tests");
        System.out.println("  duplicates  - Drop duplicates tests");
        System.out.println("  cogram      - Cogram functionality tests");
        System.out.println("  vocab       - Vocabulary boundary filter tests");
        System.out.println("  vocab_file  - Vocabulary file tests");
        System.out.println("  vocab_concat - Vocab concatenation tests");
        System.out.println("  performance - Performance benchmarks");
        System.out.println("  examples    - Usage examples");
        System.out.println("  (no args)   - Run all tests except performance");
    }
}
