package org.es.tok.config;

import org.es.tok.categ.CategConfig;
import org.es.tok.ngram.NgramConfig;
import org.es.tok.vocab.VocabConfig;

public class EsTokConfig {
    private final VocabConfig vocabConfig;
    private final CategConfig categConfig;
    private final NgramConfig ngramConfig;
    private final boolean ignoreCase;
    private final boolean dropDuplicates;

    public EsTokConfig(VocabConfig vocabConfig, CategConfig categConfig, NgramConfig ngramConfig, boolean ignoreCase, boolean dropDuplicates) {
        this.vocabConfig = vocabConfig;
        this.categConfig = categConfig;
        this.ngramConfig = ngramConfig;
        this.ignoreCase = ignoreCase;
        this.dropDuplicates = dropDuplicates;
    }

    // Constructor with default NgramConfig
    public EsTokConfig(VocabConfig vocabConfig, CategConfig categConfig, boolean ignoreCase, boolean dropDuplicates) {
        this(vocabConfig, categConfig, new NgramConfig(false, false, false, false), ignoreCase, dropDuplicates);
    }

    // Backward compatibility constructors
    public EsTokConfig(VocabConfig vocabConfig, CategConfig categConfig, NgramConfig ngramConfig, boolean ignoreCase) {
        this(vocabConfig, categConfig, ngramConfig, ignoreCase, false);
    }

    public EsTokConfig(VocabConfig vocabConfig, CategConfig categConfig, boolean ignoreCase) {
        this(vocabConfig, categConfig, new NgramConfig(false, false, false, false), ignoreCase, false);
    }

    public VocabConfig getVocabConfig() {
        return vocabConfig;
    }

    public CategConfig getCategConfig() {
        return categConfig;
    }

    public NgramConfig getNgramConfig() {
        return ngramConfig;
    }

    public boolean isIgnoreCase() {
        return ignoreCase;
    }

    public boolean isDropDuplicates() {
        return dropDuplicates;
    }

    @Override
    public String toString() {
        return String.format("EsTokConfig{vocabConfig=%s, categConfig=%s, ngramConfig=%s, ignoreCase=%s, dropDuplicates=%s}",
                vocabConfig, categConfig, ngramConfig, ignoreCase, dropDuplicates);
    }
}