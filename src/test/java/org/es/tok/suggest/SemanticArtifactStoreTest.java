package org.es.tok.suggest;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SemanticArtifactStoreTest {
    @Test
    public void testLoadsCompactTsvDirectory() throws Exception {
        Path directory = Files.createTempDirectory("semantic-artifacts");
        Files.writeString(directory.resolve("rewrite.tsv"), "康夫▂ui\tcomfyui\t1\n");
        Files.writeString(directory.resolve("synonym.tsv"), "教程\t教学\t0.92\t讲解\t0.9\n");

        SemanticArtifactStore store = SemanticArtifactStore.loadFromDirectory(directory);

        List<SemanticExpansionStore.SemanticExpansionRule> rewriteRules = store.expansions("康夫 UI");
        assertEquals(1, rewriteRules.size());
        assertEquals("comfyui", rewriteRules.get(0).text());
        assertEquals("rewrite", rewriteRules.get(0).type());

        List<String> matches = store.matchingTerms("原神 教程 入门");
        assertTrue(matches.toString(), matches.contains("教程"));

        assertEquals("comfyui", store.expansions("康夫UI").get(0).text());
    }
}