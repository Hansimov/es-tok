package org.es.tok.rest;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.Table;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.action.cat.AbstractCatAction;
import org.elasticsearch.rest.action.cat.RestTable;
import org.es.tok.EsTokPlugin;
import org.es.tok.config.EsTokConfig;
import org.es.tok.config.EsTokConfigLoader;
import org.es.tok.core.facade.EsTokEngine;
import org.es.tok.core.model.AnalysisVersion;
import org.es.tok.suggest.PinyinWarmupIndexListener;

import java.util.List;
import java.util.function.Supplier;

import static org.elasticsearch.rest.RestRequest.Method.GET;

public class RestInfoAction extends AbstractCatAction {

    private final Supplier<PinyinWarmupIndexListener.WarmupSummary> warmupSummarySupplier;

    public RestInfoAction() {
        this(() -> new PinyinWarmupIndexListener.WarmupSummary(0, 0, 0, 0));
    }

    public RestInfoAction(Supplier<PinyinWarmupIndexListener.WarmupSummary> warmupSummarySupplier) {
        this.warmupSummarySupplier = warmupSummarySupplier;
    }

    InfoSnapshot buildInfoSnapshot(String path) {
        AnalysisVersion version = resolveDiagnosticVersion();
        PinyinWarmupIndexListener.WarmupSummary warmupSummary = warmupSummarySupplier.get();
        if (path.endsWith("/version")) {
            return new InfoSnapshot(
                    "es_tok",
                    EsTokPlugin.VERSION,
                    EsTokPlugin.VERSION,
                    version.getAnalysisHash(),
                    version.getVocabHash(),
                    version.getRulesHash(),
                    warmupSummary.readyShards(),
                    warmupSummary.totalShards(),
                    warmupSummary.runningShards(),
                    warmupSummary.queuedShards(),
                    "ES-TOK plugin");
        }
        return new InfoSnapshot(
                "es_tok",
                warmupSummary.isReady() ? "Ready" : "Warming " + warmupSummary.readyShards() + "/" + warmupSummary.totalShards(),
                EsTokPlugin.VERSION,
                version.getAnalysisHash(),
                version.getVocabHash(),
                version.getRulesHash(),
                warmupSummary.readyShards(),
                warmupSummary.totalShards(),
                warmupSummary.runningShards(),
                warmupSummary.queuedShards(),
                warmupSummary.isReady() ? "ES-TOK plugin" : "ES-TOK plugin warmup in progress");
    }

    private AnalysisVersion resolveDiagnosticVersion() {
        EsTokConfig config = EsTokConfigLoader.loadConfig(
                Settings.builder()
                        .put("use_vocab", false)
                        .put("use_categ", true)
                        .put("use_ngram", false)
                        .put("use_rules", false)
                        .build(),
                null,
                true);
        return new EsTokEngine(config).resolveVersion();
    }

    @Override
    public List<Route> routes() {
        return List.of(
                new Route(GET, "/_cat/es_tok"),
                new Route(GET, "/_cat/es_tok/version"));
    }

    @Override
    public String getName() {
        return "es_tok_info_action";
    }

    @Override
    protected RestChannelConsumer doCatRequest(final RestRequest request, final NodeClient client) {
        InfoSnapshot snapshot = buildInfoSnapshot(request.path());
        Table table = getTableWithHeader(request);
        table.startRow();
        table.addCell(snapshot.plugin());
        table.addCell(snapshot.status());
        table.addCell(snapshot.pluginVersion());
        table.addCell(snapshot.analysisHash());
        table.addCell(snapshot.vocabHash());
        table.addCell(snapshot.rulesHash());
        table.addCell(snapshot.warmupReadyShards());
        table.addCell(snapshot.warmupTotalShards());
        table.addCell(snapshot.warmupRunningShards());
        table.addCell(snapshot.warmupQueuedShards());
        table.addCell(snapshot.description());
        table.endRow();
        return channel -> {
            try {
                channel.sendResponse(RestTable.buildResponse(table, channel));
            } catch (final Exception e) {
                channel.sendResponse(new RestResponse(channel, e));
            }
        };
    }

    @Override
    protected void documentation(StringBuilder sb) {
        sb.append("/_cat/es_tok\n");
        sb.append("/_cat/es_tok/version\n");
    }

    @Override
    protected Table getTableWithHeader(RestRequest request) {
        final Table table = new Table();
        table.startHeaders();
        table.addCell("plugin", "desc:plugin name");
        table.addCell("status", "desc:plugin status/version");
        table.addCell("plugin_version", "desc:plugin code version");
        table.addCell("analysis_hash", "desc:diagnostic analysis hash");
        table.addCell("vocab_hash", "desc:diagnostic vocab hash");
        table.addCell("rules_hash", "desc:diagnostic rules hash");
        table.addCell("warmup_ready_shards", "desc:ready business shards");
        table.addCell("warmup_total_shards", "desc:tracked business shards");
        table.addCell("warmup_running_shards", "desc:business shards currently warming");
        table.addCell("warmup_queued_shards", "desc:business shards queued for warmup");
        table.addCell("description", "desc:plugin description");
        table.endHeaders();
        return table;
    }

    record InfoSnapshot(
            String plugin,
            String status,
            String pluginVersion,
            String analysisHash,
            String vocabHash,
            String rulesHash,
            int warmupReadyShards,
            int warmupTotalShards,
            int warmupRunningShards,
            int warmupQueuedShards,
            String description) {
    }
}