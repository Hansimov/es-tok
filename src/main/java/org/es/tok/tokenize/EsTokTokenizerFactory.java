package org.es.tok.tokenize;

import org.apache.lucene.analysis.Tokenizer;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenizerFactory;

import java.util.List;
import java.util.Arrays;

public class EsTokTokenizerFactory extends AbstractTokenizerFactory {
    private final boolean enableVocab;
    private final boolean enableCateg;
    private final List<String> vocabs;
    private final boolean caseSensitive;

    public EsTokTokenizerFactory(IndexSettings indexSettings, Environment environment, String name, Settings settings) {
        super(indexSettings, settings, name);

        this.enableVocab = settings.getAsBoolean("enable_vocab", true);
        this.enableCateg = settings.getAsBoolean("enable_categ", false);
        this.vocabs = settings.getAsList("vocabs", settings.getAsList("vocabulary", Arrays.asList()));
        this.caseSensitive = settings.getAsBoolean("case_sensitive", false);
    }

    @Override
    public Tokenizer create() {
        return new EsTokTokenizer(enableVocab, enableCateg, vocabs, caseSensitive);
    }
}