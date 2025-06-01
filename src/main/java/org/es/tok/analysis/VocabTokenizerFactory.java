package org.es.tok.analysis;

import org.apache.lucene.analysis.Tokenizer;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenizerFactory;
import org.es.tok.lucene.VocabTokenizer;

import java.util.List;

public class VocabTokenizerFactory extends AbstractTokenizerFactory {
    private final List<String> vocabulary;
    private final boolean caseSensitive;

    public VocabTokenizerFactory(IndexSettings indexSettings, Environment environment, String name,
            Settings settings) {
        super(indexSettings, settings, name);
        this.vocabulary = settings.getAsList("vocabulary");
        this.caseSensitive = settings.getAsBoolean("case_sensitive", false);
    }

    @Override
    public Tokenizer create() {
        return new VocabTokenizer(vocabulary, caseSensitive);
    }
}