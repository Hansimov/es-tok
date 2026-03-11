package org.es.tok.rest;

import org.es.tok.suggest.PinyinWarmupIndexListener;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

public class RestInfoActionTest {

    @Test
    public void testReadySnapshotUsesSharedVersionFieldNames() {
        RestInfoAction action = new RestInfoAction(() -> new PinyinWarmupIndexListener.WarmupSummary(8, 8, 0, 0));
        RestInfoAction.InfoSnapshot snapshot = action.buildInfoSnapshot("/_cat/es_tok");

        assertEquals("es_tok", snapshot.plugin());
        assertEquals("Ready", snapshot.status());
        assertEquals("0.10.0", snapshot.pluginVersion());
        assertNotNull(snapshot.analysisHash());
        assertEquals("disabled", snapshot.vocabHash());
        assertEquals("disabled", snapshot.rulesHash());
        assertEquals(8, snapshot.warmupReadyShards());
        assertEquals(8, snapshot.warmupTotalShards());
        assertEquals(0, snapshot.warmupRunningShards());
        assertEquals(0, snapshot.warmupQueuedShards());
    }

    @Test
    public void testVersionSnapshotSwitchesStatusToPluginVersion() {
        RestInfoAction action = new RestInfoAction(() -> new PinyinWarmupIndexListener.WarmupSummary(8, 8, 0, 0));
        RestInfoAction.InfoSnapshot ready = action.buildInfoSnapshot("/_cat/es_tok");
        RestInfoAction.InfoSnapshot version = action.buildInfoSnapshot("/_cat/es_tok/version");

        assertEquals(version.pluginVersion(), version.status());
        assertNotEquals(ready.status(), version.status());
        assertEquals(ready.analysisHash(), version.analysisHash());
        assertEquals(ready.vocabHash(), version.vocabHash());
        assertEquals(ready.rulesHash(), version.rulesHash());
    }

    @Test
    public void testReadySnapshotShowsWarmupProgressWhenBusinessShardsStillWarming() {
        RestInfoAction action = new RestInfoAction(() -> new PinyinWarmupIndexListener.WarmupSummary(8, 3, 2, 3));
        RestInfoAction.InfoSnapshot snapshot = action.buildInfoSnapshot("/_cat/es_tok");

        assertEquals("Warming 3/8", snapshot.status());
        assertEquals(3, snapshot.warmupReadyShards());
        assertEquals(8, snapshot.warmupTotalShards());
        assertEquals(2, snapshot.warmupRunningShards());
        assertEquals(3, snapshot.warmupQueuedShards());
        assertEquals("ES-TOK plugin warmup in progress", snapshot.description());
    }
}