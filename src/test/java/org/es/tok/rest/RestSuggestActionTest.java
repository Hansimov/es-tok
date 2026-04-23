package org.es.tok.rest;

import org.es.tok.action.EsTokSuggestRequest;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RestSuggestActionTest {

    @Test
    public void testBuildRequestFromPayloadMap() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", "github");
        payload.put("mode", "associate");
        payload.put("fields", List.of("content", "title"));
        payload.put("size", 7);
        payload.put("scan_limit", 80);
        payload.put("min_prefix_length", 2);
        payload.put("min_candidate_length", 3);
        payload.put("allow_compact_bigrams", false);
        payload.put("cache", false);
        payload.put("use_pinyin", true);
        payload.put("max_fields", 4);

        EsTokSuggestRequest request = RestSuggestAction.buildRequest(payload, new String[] { "my_index" });

        assertEquals("github", request.text());
        assertEquals("associate", request.mode());
        assertEquals(List.of("content", "title"), request.fields());
        assertEquals(7, request.size());
        assertEquals(80, request.scanLimit());
        assertEquals(2, request.minPrefixLength());
        assertEquals(3, request.minCandidateLength());
        assertTrue(request.allowCompactBigrams() == false);
        assertTrue(request.useCache() == false);
        assertTrue(request.usePinyin());
        assertEquals(4, request.maxFields());
        assertArrayEquals(new String[] { "my_index" }, request.indices());
    }

    @Test
    public void testCommaSeparatedFieldsAreParsed() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", "git");
        payload.put("fields", "content, title , tags");

        EsTokSuggestRequest request = RestSuggestAction.buildRequest(payload, new String[0]);

        assertEquals(List.of("content", "title", "tags"), request.fields());
    }

    @Test
    public void testCorrectionModeParametersAreParsed() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", "gihtub copolit");
        payload.put("mode", "correction");
        payload.put("fields", List.of("content"));
        payload.put("correction_rare_doc_freq", 0);
        payload.put("correction_min_length", 4);
        payload.put("correction_max_edits", 2);
        payload.put("correction_prefix_length", 1);

        EsTokSuggestRequest request = RestSuggestAction.buildRequest(payload, new String[] { "idx" });

        assertEquals("correction", request.mode());
        assertEquals(0, request.correctionRareDocFreq());
        assertEquals(4, request.correctionMinLength());
        assertEquals(2, request.correctionMaxEdits());
        assertEquals(1, request.correctionPrefixLength());
    }

    @Test
    public void testAutoModeParametersAreParsed() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", "老师");
        payload.put("mode", "auto");
        payload.put("fields", List.of("content"));
        payload.put("size", 8);
        payload.put("scan_limit", 96);
        payload.put("correction_min_length", 2);

        EsTokSuggestRequest request = RestSuggestAction.buildRequest(payload, new String[] { "idx" });

        assertEquals("auto", request.mode());
        assertEquals(8, request.size());
        assertEquals(96, request.scanLimit());
        assertEquals(2, request.correctionMinLength());
    }

    @Test
    public void testSemanticModeParametersAreParsed() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", "袁启 专访");
        payload.put("mode", "semantic");
        payload.put("fields", List.of("content"));
        payload.put("size", 6);

        EsTokSuggestRequest request = RestSuggestAction.buildRequest(payload, new String[] { "idx" });

        assertEquals("semantic", request.mode());
        assertEquals(6, request.size());
        assertEquals(List.of("content"), request.fields());
    }

    @Test
    public void testChinesePinyinRequestLowersImplicitCorrectionMinLength() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", "俞利均");
        payload.put("mode", "auto");
        payload.put("fields", List.of("content"));
        payload.put("use_pinyin", true);

        EsTokSuggestRequest request = RestSuggestAction.buildRequest(payload, new String[] { "idx" });

        assertEquals(2, request.correctionMinLength());
    }

    @Test
    public void testPrewarmPinyinAllowsEmptyText() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "prefix");
        payload.put("fields", List.of("content"));
        payload.put("prewarm_pinyin", true);

        EsTokSuggestRequest request = RestSuggestAction.buildRequest(payload, new String[] { "idx" });

        assertTrue(request.prewarmPinyin());
        assertTrue(request.usePinyin());
        assertNull(request.validate());
    }
}