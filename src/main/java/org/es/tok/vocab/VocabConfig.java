package org.es.tok.vocab;

import java.util.List;

public class VocabConfig {
    private final boolean useVocab;
    private final List<String> vocabs;

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

    @Override
    public String toString() {
        return String.format("VocabConfig{useVocab=%s, vocabs=%d terms}", 
                useVocab, vocabs != null ? vocabs.size() : 0);
    }
}