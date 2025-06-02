package org.es.tok.tokenize;

import org.apache.lucene.analysis.Tokenizer;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenizerFactory;

import java.util.List;
import java.util.Arrays;

public class EsTokTokenizerFactory extends AbstractTokenizerFactory {
    private final boolean useVocab;
    private final boolean useCateg;
    private final List<String> vocabs;
    private final boolean ignoreCase;
    private final boolean splitWord;

    public EsTokTokenizerFactory(IndexSettings indexSettings, Environment environment, String name, Settings settings) {
        super(indexSettings, settings, name);

        this.useVocab = settings.getAsBoolean("use_vocab", true);
        this.useCateg = settings.getAsBoolean("use_categ", false);
        this.vocabs = settings.getAsList("vocabs", Arrays.asList());
        this.ignoreCase = settings.getAsBoolean("ignore_case", true);
        this.splitWord = settings.getAsBoolean("split_word", true);
    }

    @Override
    public Tokenizer create() {
        return new EsTokTokenizer(useVocab, useCateg, vocabs, ignoreCase, splitWord);
    }
}