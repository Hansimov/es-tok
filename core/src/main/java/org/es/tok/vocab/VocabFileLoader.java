package org.es.tok.vocab;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class VocabFileLoader {
    static final String DEFAULT_VOCAB_FILE = "vocabs.txt";
    static final int DEFAULT_VOCAB_SIZE = 2680000;

    // Thread-safe singleton cache for default vocabs (loaded only once)
    private static volatile List<String> cachedDefaultVocabs;
    private static final Object DEFAULT_VOCABS_LOCK = new Object();

    public static List<String> loadVocabs(Settings settings, Environment environment) {
        return loadVocabs(settings, environment, false);
    }

    public static List<String> loadVocabs(Settings settings, Environment environment, boolean useCache) {
        if (useCache) {
            return VocabCache.loadVocabsWithCache(settings, environment);
        } else {
            return loadVocabsInternal(settings, environment);
        }
    }

    static List<String> loadVocabsInternal(Settings settings, Environment environment) {
        boolean useVocab = settings.getAsBoolean("use_vocab", true);
        if (!useVocab) {
            return new ArrayList<>();
        }
        Settings vocabConfig = settings.getAsSettings("vocab_config");
        if (vocabConfig == null || vocabConfig.isEmpty()) {
            // No vocab config provided — return empty list.
            // Default vocabs must be explicitly configured via vocab_config.file
            // to avoid accidentally loading 2.68M words (which builds a ~1-4GB Trie).
            return new ArrayList<>();
        }
        List<String> vocabs = loadVocabsFromConfigInEnv(vocabConfig, environment);
        return vocabs;
    }

    /**
     * Load the default vocabs.txt from the plugin directory or classpath.
     * Uses double-checked locking to ensure vocabs are loaded only once
     * (thread-safe singleton).
     * This prevents OOM from concurrent REST requests each building their own
     * Aho-Corasick trie.
     */
    static List<String> loadDefaultVocabs() {
        List<String> cached = cachedDefaultVocabs;
        if (cached != null) {
            return cached;
        }
        synchronized (DEFAULT_VOCABS_LOCK) {
            if (cachedDefaultVocabs != null) {
                return cachedDefaultVocabs;
            }
            List<String> vocabs = loadDefaultVocabsFromSource();
            cachedDefaultVocabs = vocabs;
            return vocabs;
        }
    }

    /**
     * Actually load default vocabs from plugin directory or classpath.
     * Streams with size limit to avoid loading all entries into memory.
     */
    private static List<String> loadDefaultVocabsFromSource() {
        // Try plugin directory first
        Path pluginDir = Path.of("/usr/share/elasticsearch/plugins/es_tok");
        Path defaultFile = pluginDir.resolve(DEFAULT_VOCAB_FILE);
        if (Files.exists(defaultFile)) {
            return loadVocabsFromFilePathWithLimit(defaultFile, DEFAULT_VOCAB_SIZE);
        }

        // Fall back to classpath (inside JAR)
        return loadDefaultVocabsFromClasspath();
    }

    /**
     * Load vocabs from a file path with a size limit, streaming line by line.
     */
    private static List<String> loadVocabsFromFilePathWithLimit(Path filePath, int sizeLimit) {
        List<String> vocabs = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (sizeLimit >= 0 && count >= sizeLimit) {
                    break;
                }
                String[] parts = line.split(",");
                if (parts.length > 0) {
                    String word = parts[0].trim();
                    if (!word.isEmpty()) {
                        vocabs.add(word);
                        count++;
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load default vocab file: " + filePath, e);
        }
        return vocabs;
    }

    /**
     * Load default vocabs from classpath (inside JAR), streaming with size limit.
     */
    private static List<String> loadDefaultVocabsFromClasspath() {
        try (InputStream is = VocabFileLoader.class.getResourceAsStream("/" + DEFAULT_VOCAB_FILE)) {
            if (is == null) {
                return new ArrayList<>();
            }
            List<String> vocabs = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null) {
                    if (DEFAULT_VOCAB_SIZE >= 0 && count >= DEFAULT_VOCAB_SIZE) {
                        break;
                    }
                    String[] parts = line.split(",");
                    if (parts.length > 0) {
                        String word = parts[0].trim();
                        if (!word.isEmpty()) {
                            vocabs.add(word);
                            count++;
                        }
                    }
                }
            }
            return vocabs;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static List<String> loadVocabsFromConfigInEnv(Settings vocabConfig, Environment environment) {
        List<String> allVocabs = new ArrayList<>();

        // Handle case where no vocab_config is provided
        if (vocabConfig == null) {
            return allVocabs; // Return empty list
        }

        // Load vocabs from "list"
        List<String> listVocabs = vocabConfig.getAsList("list", Arrays.asList());
        allVocabs.addAll(listVocabs);

        // Load vocabs from "file"
        String vocabFile = vocabConfig.get("file");
        if (vocabFile != null && !vocabFile.trim().isEmpty()) {
            Path filePath = getVocabFileFullPath(vocabFile, environment);
            List<String> fileVocabs;
            if (Files.exists(filePath)) {
                fileVocabs = loadVocabsFromFilePath(filePath);
            } else {
                // Fallback: try loading from classpath (inside JAR)
                fileVocabs = loadVocabsFromClasspathFile(vocabFile, -1);
                if (fileVocabs.isEmpty()) {
                    throw new IllegalArgumentException(
                            "vocab file not exist: [%s] (also not found in classpath)".formatted(filePath));
                }
            }
            allVocabs.addAll(fileVocabs);
        }

        // Apply size limit if specified
        int size = vocabConfig.getAsInt("size", -1);
        if (size >= 0 && size < allVocabs.size()) {
            return allVocabs.subList(0, size);
        }

        return allVocabs;
    }

    /**
     * Load vocabs from classpath by filename, with optional size limit.
     * Used as fallback when file is not found at plugin directory path.
     */
    static List<String> loadVocabsFromClasspathFile(String filename, int sizeLimit) {
        try (InputStream is = VocabFileLoader.class.getResourceAsStream("/" + filename)) {
            if (is == null) {
                return new ArrayList<>();
            }
            List<String> vocabs = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null) {
                    if (sizeLimit >= 0 && count >= sizeLimit) {
                        break;
                    }
                    String[] parts = line.split(",");
                    if (parts.length > 0) {
                        String word = parts[0].trim();
                        if (!word.isEmpty()) {
                            vocabs.add(word);
                            count++;
                        }
                    }
                }
            }
            return vocabs;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    static Path getVocabFileFullPath(String vocabFile, Environment environment) {
        Path pluginDir = Path.of("/usr/share/elasticsearch/plugins/es_tok");
        return pluginDir.resolve(vocabFile);
    }

    public static List<String> loadVocabsFromFilePath(Path filePath) {
        List<String> fileVocabs = new ArrayList<>();

        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("vocab file not exist: [%s]".formatted(filePath));
        }

        try (Stream<String> lines = Files.lines(filePath)) {
            lines.forEach(line -> {
                // Each line is word (+ score/frequency)
                String[] parts = line.split(",");
                if (parts.length > 0) {
                    // Only keep the word part
                    String word = parts[0].trim();
                    if (!word.isEmpty()) {
                        fileVocabs.add(word);
                    }
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to load vocab file: " + filePath, e);
        }
        return fileVocabs;
    }
}