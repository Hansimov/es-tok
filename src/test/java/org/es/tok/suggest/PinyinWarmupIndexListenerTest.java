package org.es.tok.suggest;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PinyinWarmupIndexListenerTest {

    @Test
    public void testDiscoverWarmupFieldsUsesOnlyMappedPreferredFields() {
        List<String> warmupFields = PinyinWarmupIndexListener.discoverWarmupFields(
                "bili_videos_dev6",
                Set.of(
                        "title.suggest",
                        "title.assoc",
                        "title.words",
                        "tags.suggest",
                        "owner.name.suggest",
                        "owner.name.keyword"));

        assertEquals(
                List.of("owner.name.suggest", "title.assoc", "title.suggest", "tags.suggest"),
                warmupFields);
    }

    @Test
    public void testDiscoverWarmupFieldsSkipsSystemIndices() {
        List<String> warmupFields = PinyinWarmupIndexListener.discoverWarmupFields(
                ".security-7",
                Set.of("title.suggest", "title.words", "tags.suggest"));

        assertTrue(warmupFields.isEmpty());
    }

    @Test
    public void testShouldWarmIndexOnlyAllowsBusinessIndices() {
        assertTrue(PinyinWarmupIndexListener.shouldWarmIndex("bili_videos_dev6"));
        assertFalse(PinyinWarmupIndexListener.shouldWarmIndex(".security-7"));
        assertFalse(PinyinWarmupIndexListener.shouldWarmIndex(null));
    }
}