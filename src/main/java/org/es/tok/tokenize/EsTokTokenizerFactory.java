package org.es.tok.tokenize;

import org.apache.lucene.analysis.Tokenizer;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenizerFactory;
import org.es.tok.config.EsTokConfig;
import org.es.tok.config.EsTokConfigLoader;

public class EsTokTokenizerFactory extends AbstractTokenizerFactory {
    private final EsTokConfig config;

    public EsTokTokenizerFactory(IndexSettings indexSettings, Environment environment, String name, Settings settings) {
        super(indexSettings, settings, name);

        this.config = EsTokConfigLoader.loadConfig(settings, environment);
    }

    @Override
    public Tokenizer create() {
        return new EsTokTokenizer(config);
    }
}