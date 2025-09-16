package org.es.tok;

import org.es.tok.config.EsTokConfig;
import org.es.tok.extra.ExtraConfig;
import org.es.tok.categ.CategConfig;
import org.es.tok.ngram.NgramConfig;
import org.es.tok.vocab.VocabConfig;

import java.util.Arrays;
import java.util.List;

/**
 * Builder class for creating EsTokConfig instances in tests
 */
public class ConfigBuilder {
    private boolean useVocab = false;
    private List<String> vocabs = Arrays.asList();
    private boolean useCateg = false;
    private boolean splitWord = false;
    private boolean useNgram = false;
    private boolean useBigram = false;
    private boolean useVcgram = false;
    private boolean useVbgram = false;
    private boolean dropCogram = true;
    private boolean ignoreCase = false;
    private boolean ignoreHant = false;
    private boolean dropDuplicates = false;

    public static ConfigBuilder create() {
        return new ConfigBuilder();
    }

    // Vocab settings
    public ConfigBuilder withVocab(String... vocabs) {
        this.useVocab = true;
        this.vocabs = Arrays.asList(vocabs);
        return this;
    }

    public ConfigBuilder withVocab(List<String> vocabs) {
        this.useVocab = true;
        this.vocabs = vocabs;
        return this;
    }

    // Categ settings
    public ConfigBuilder withCateg() {
        this.useCateg = true;
        return this;
    }

    public ConfigBuilder withCategSplitWord() {
        this.useCateg = true;
        this.splitWord = true;
        return this;
    }

    // Ngram settings
    public ConfigBuilder withBigram() {
        this.useNgram = true;
        this.useBigram = true;
        return this;
    }

    public ConfigBuilder withVcgram() {
        this.useNgram = true;
        this.useVcgram = true;
        return this;
    }

    public ConfigBuilder withVbgram() {
        this.useNgram = true;
        this.useVbgram = true;
        return this;
    }

    public ConfigBuilder withAllNgrams() {
        this.useNgram = true;
        this.useBigram = true;
        this.useVcgram = true;
        this.useVbgram = true;
        return this;
    }

    public ConfigBuilder withDropCogram(boolean dropCogram) {
        this.dropCogram = dropCogram;
        return this;
    }

    // Extra settings
    public ConfigBuilder withIgnoreCase() {
        this.ignoreCase = true;
        return this;
    }

    public ConfigBuilder withIgnoreHant() {
        this.ignoreHant = true;
        return this;
    }

    public ConfigBuilder withDropDuplicates() {
        this.dropDuplicates = true;
        return this;
    }

    public EsTokConfig build() {
        ExtraConfig extraConfig = new ExtraConfig(ignoreCase, ignoreHant, dropDuplicates);
        CategConfig categConfig = new CategConfig(useCateg, splitWord);
        VocabConfig vocabConfig = new VocabConfig(useVocab, vocabs);
        NgramConfig ngramConfig = new NgramConfig(useNgram, useBigram, useVcgram, useVbgram, dropCogram);

        return new EsTokConfig(extraConfig, categConfig, vocabConfig, ngramConfig);
    }
}
