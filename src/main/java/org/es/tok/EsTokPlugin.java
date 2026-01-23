package org.es.tok;

import org.apache.lucene.analysis.Analyzer;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.index.analysis.AnalyzerProvider;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.indices.analysis.AnalysisModule.AnalysisProvider;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.es.tok.analysis.EsTokAnalyzerProvider;
import org.es.tok.query.EsTokQueryStringQueryBuilder;
import org.es.tok.rest.RestInfoAction;
import org.es.tok.tokenize.EsTokTokenizerFactory;
import org.es.tok.rest.RestAnalyzeAction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class EsTokPlugin extends Plugin implements AnalysisPlugin, ActionPlugin, SearchPlugin {
    public static final String VERSION = "0.8.1";

    @Override
    public Map<String, AnalysisProvider<TokenizerFactory>> getTokenizers() {
        Map<String, AnalysisProvider<TokenizerFactory>> tokenizers = new HashMap<>();
        tokenizers.put("es_tok", EsTokTokenizerFactory::new);
        return tokenizers;
    }

    @Override
    public Map<String, AnalysisProvider<AnalyzerProvider<? extends Analyzer>>> getAnalyzers() {
        Map<String, AnalysisProvider<AnalyzerProvider<? extends Analyzer>>> analyzers = new HashMap<>();
        analyzers.put("es_tok", EsTokAnalyzerProvider::new);
        return analyzers;
    }

    @Override
    public List<RestHandler> getRestHandlers(
            final Settings settings,
            final NamedWriteableRegistry namedWriteableRegistry,
            final RestController restController,
            final ClusterSettings clusterSettings,
            final IndexScopedSettings indexScopedSettings,
            final SettingsFilter settingsFilter,
            final IndexNameExpressionResolver indexNameExpressionResolver,
            final Supplier<DiscoveryNodes> nodesInCluster,
            final Predicate<NodeFeature> clusterSupportsFeature) {
        return List.of(
                new RestInfoAction(),
                new RestAnalyzeAction());
    }

    @Override
    public List<QuerySpec<?>> getQueries() {
        return List.of(
                new QuerySpec<>(
                        EsTokQueryStringQueryBuilder.NAME,
                        EsTokQueryStringQueryBuilder::new,
                        EsTokQueryStringQueryBuilder::fromXContent));
    }
}
