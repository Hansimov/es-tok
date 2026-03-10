package org.es.tok.rest;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

public class RestInfoActionTest {

    @Test
    public void testReadySnapshotUsesSharedVersionFieldNames() {
        RestInfoAction action = new RestInfoAction();
        RestInfoAction.InfoSnapshot snapshot = action.buildInfoSnapshot("/_cat/es_tok");

        assertEquals("es_tok", snapshot.plugin());
        assertEquals("Ready", snapshot.status());
        assertEquals("0.10.0", snapshot.pluginVersion());
        assertNotNull(snapshot.analysisHash());
        assertEquals("disabled", snapshot.vocabHash());
        assertEquals("disabled", snapshot.rulesHash());
    }

    @Test
    public void testVersionSnapshotSwitchesStatusToPluginVersion() {
        RestInfoAction action = new RestInfoAction();
        RestInfoAction.InfoSnapshot ready = action.buildInfoSnapshot("/_cat/es_tok");
        RestInfoAction.InfoSnapshot version = action.buildInfoSnapshot("/_cat/es_tok/version");

        assertEquals(version.pluginVersion(), version.status());
        assertNotEquals(ready.status(), version.status());
        assertEquals(ready.analysisHash(), version.analysisHash());
        assertEquals(ready.vocabHash(), version.vocabHash());
        assertEquals(ready.rulesHash(), version.rulesHash());
    }
}