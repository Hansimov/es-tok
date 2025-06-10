package org.es.tok.file;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class VocabLoader {
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
        return loadVocabsFromConfigInEnv(vocabConfig, environment);
    }

    private static List<String> loadVocabsFromConfigInEnv(Settings vocabConfig, Environment environment) {
        List<String> allVocabs = new ArrayList<>();

        // Load vocabs from "list"
        List<String> listVocabs = vocabConfig.getAsList("list", Arrays.asList());
        allVocabs.addAll(listVocabs);

        // Load vocabs from "file"
        String vocabFile = vocabConfig.get("file");
        if (vocabFile != null && !vocabFile.trim().isEmpty()) {
            List<String> fileVocabs = loadVocabsFromFileInEnv(vocabFile, environment);
            allVocabs.addAll(fileVocabs);
        }

        // Apply size limit if specified
        int size = vocabConfig.getAsInt("size", -1);
        if (size >= 0 && size < allVocabs.size()) {
            return allVocabs.subList(0, size);
        }

        return allVocabs;
    }

    static List<String> loadVocabsFromFileInEnv(String vocabFile, Environment environment) {
        Path filePath;
        if (environment == null) {
            // Environment is not available for REST API, fallback implementation is needed
            filePath = Path.of("/usr/share/elasticsearch/plugins/" + vocabFile);
        } else {
            filePath = environment.configFile().resolve(vocabFile);
        }

        List<String> fileVocabs = loadVocabsFromFilePath(filePath);
        return fileVocabs;
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