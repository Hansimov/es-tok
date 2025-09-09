package org.es.tok.analysis;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractIndexAnalyzerProvider;
import org.es.tok.config.EsTokConfig;
import org.es.tok.config.EsTokConfigLoader;

public class EsTokAnalyzerProvider extends AbstractIndexAnalyzerProvider<EsTokAnalyzer> {
    private final EsTokAnalyzer analyzer;

    public EsTokAnalyzerProvider(IndexSettings indexSettings, Environment environment, String name, Settings settings) {
        super(name);

        // Load unified configuration
        EsTokConfig config = EsTokConfigLoader.loadConfig(settings, environment);
        this.analyzer = new EsTokAnalyzer(config);
    }

    @Override
    public EsTokAnalyzer get() {
        return this.analyzer;
    }
}