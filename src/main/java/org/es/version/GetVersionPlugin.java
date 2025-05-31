package org.es.version;

import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.features.NodeFeature;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Minimal ES plugin exposing GET /_plugin/estok/version
 */
public class GetVersionPlugin extends Plugin implements ActionPlugin {
    /** Keep this in sync with your build.gradle version! */
    public static final String VERSION = "0.1.0";

    @Override
    public Collection<RestHandler> getRestHandlers(
            Settings settings,
            NamedWriteableRegistry namedWriteableRegistry,
            RestController restController,
            ClusterSettings clusterSettings,
            IndexScopedSettings indexScopedSettings,
            SettingsFilter settingsFilter,
            IndexNameExpressionResolver indexNameExpressionResolver,
            Supplier<DiscoveryNodes> nodesInCluster,
            Predicate<NodeFeature> clusterSupportsFeature) {
        return List.of(new RestGetVersionAction());
    }
}
