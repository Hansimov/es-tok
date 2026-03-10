package org.es.tok.vocab;

import org.es.tok.strategy.VocabStrategy;

import java.util.List;

public class VocabConfig {
    private final boolean useVocab;
    private final List<String> vocabs;

    // Lazily-initialized cached VocabStrategy; volatile for thread-safe
    // double-checked read. VocabStrategy.getOrCreate() itself is thread-safe
    // (ConcurrentHashMap.computeIfAbsent), so the worst case if two threads
    // race is two redundant — but harmless — lookups.
    private volatile VocabStrategy cachedStrategy;

    public VocabConfig(boolean useVocab, List<String> vocabs) {
        this.useVocab = useVocab;
        this.vocabs = vocabs;
    }

    public boolean isUseVocab() {
        return useVocab;
    }

    public List<String> getVocabs() {
        return vocabs;
    }

    /**
     * Get or create a globally-cached VocabStrategy for this config's vocab list.
     * The Aho-Corasick Trie is shared across all indexes and REST calls that use
     * the same vocab list — building it is expensive (1-4 GB for millions of
     * words), so this avoids duplicate construction and OOM.
     *
     * @return a shared VocabStrategy, or null if vocab is disabled / empty.
     */
    public VocabStrategy getOrCreateStrategy() {
        if (!useVocab || vocabs == null || vocabs.isEmpty()) {
            return null;
        }
        VocabStrategy s = cachedStrategy;
        if (s != null) {
            return s;
        }
        s = VocabStrategy.getOrCreate(vocabs);
        cachedStrategy = s;
        return s;
    }

    @Override
    public String toString() {
        return String.format("VocabConfig{useVocab=%s, vocabs=%d terms}",
                useVocab, vocabs != null ? vocabs.size() : 0);
    }
}