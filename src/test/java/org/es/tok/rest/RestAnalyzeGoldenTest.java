package org.es.tok.rest;

import org.es.tok.core.compat.AnalysisPayloadService;
import org.es.tok.support.GoldenAnalysisCaseLoader;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RestAnalyzeGoldenTest {
    @Test
    public void testGoldenCorpusMatchesRestAnalyzePayloadService() throws Exception {
        AnalysisPayloadService service = new AnalysisPayloadService();
        for (GoldenAnalysisCaseLoader.GoldenCase goldenCase : GoldenAnalysisCaseLoader.loadCases()) {
            assertEquals(goldenCase.name, goldenCase.expected, service.analyze(goldenCase.request));
        }
    }
}