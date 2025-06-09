package org.es.tok.file;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class VocabCache {
    private static final ConcurrentHashMap<String, CachedVocab> cache = new ConcurrentHashMap<>();

    public static List<String> loadVocabsWithCache(Settings settings, Environment environment) {
        // Create a cache key based on the vocab configuration
        String cacheKey = createCacheKey(settings, environment);

        CachedVocab cached = cache.get(cacheKey);

        // Check if we have a valid cached entry
        if (cached != null && cached.isValid(settings, environment)) {
            return cached.vocabs;
        }

        // Load fresh vocabs and cache them
        List<String> vocabs = VocabLoader.loadVocabs(settings, environment);
        CachedVocab newCached = new CachedVocab(vocabs, settings, environment);
        cache.put(cacheKey, newCached);

        return vocabs;
    }

    private static String createCacheKey(Settings settings, Environment environment) {
        StringBuilder keyBuilder = new StringBuilder();

        // Include use_vocab flag
        keyBuilder.append("use_vocab:").append(settings.getAsBoolean("use_vocab", true)).append("|");

        Settings vocabConfig = settings.getAsSettings("vocab_config");
        if (vocabConfig != null && !vocabConfig.isEmpty()) {
            // Include file path
            String file = vocabConfig.get("file");
            if (file != null) {
                keyBuilder.append("file:").append(file).append("|");
            }

            // Include list contents
            List<String> list = vocabConfig.getAsList("list");
            if (!list.isEmpty()) {
                keyBuilder.append("list:").append(String.join(",", list)).append("|");
            }

            // Include size
            int size = vocabConfig.getAsInt("size", -1);
            keyBuilder.append("size:").append(size).append("|");
        } else {
            // Legacy vocabs parameter
            List<String> legacyVocabs = settings.getAsList("vocabs");
            if (!legacyVocabs.isEmpty()) {
                keyBuilder.append("vocabs:").append(String.join(",", legacyVocabs)).append("|");
            }
        }

        return keyBuilder.toString();
    }

    public static void clearCache() {
        cache.clear();
    }

    public static int getCacheSize() {
        return cache.size();
    }

    private static class CachedVocab {
        final List<String> vocabs;
        final String configHash;
        final FileTime fileLastModified;
        final String filePath;

        CachedVocab(List<String> vocabs, Settings settings, Environment environment) {
            this.vocabs = vocabs;
            this.configHash = createCacheKey(settings, environment);

            // Track file modification time if a file is used
            Settings vocabConfig = settings.getAsSettings("vocab_config");
            String file = null;
            FileTime lastModified = null;

            if (vocabConfig != null) {
                file = vocabConfig.get("file");
                if (file != null && environment != null) {
                    try {
                        Path filePath = environment.configFile().resolve(file);
                        if (Files.exists(filePath)) {
                            lastModified = Files.getLastModifiedTime(filePath);
                        }
                    } catch (IOException e) {
                        // Ignore, will force refresh on next access
                    }
                }
            }

            this.filePath = file;
            this.fileLastModified = lastModified;
        }

        boolean isValid(Settings settings, Environment environment) {
            // Check if configuration has changed
            String currentConfigHash = createCacheKey(settings, environment);
            if (!Objects.equals(this.configHash, currentConfigHash)) {
                return false;
            }

            // Check if file has been modified (if file is used)
            if (filePath != null && environment != null) {
                try {
                    Path path = environment.configFile().resolve(filePath);
                    if (Files.exists(path)) {
                        FileTime currentLastModified = Files.getLastModifiedTime(path);
                        if (!Objects.equals(this.fileLastModified, currentLastModified)) {
                            return false;
                        }
                    } else if (this.fileLastModified != null) {
                        // File was deleted
                        return false;
                    }
                } catch (IOException e) {
                    // If we can't check file modification time, consider cache invalid
                    return false;
                }
            }

            return true;
        }
    }
}