package org.es.tok.core.analysis;

import org.elasticsearch.common.settings.Settings;
import org.es.tok.vocab.VocabLoader;
import org.es.tok.vocab.VocabFileLoader;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VocabFileLoaderTest {

    @Test
    public void testLoadVocabsFromClasspathFallbackFile() {
        Settings settings = Settings.builder()
            .put("use_vocab", true)
            .put("vocab_config.file", "vocabs.txt")
            .put("vocab_config.size", 5)
            .build();
        List<String> vocabs = VocabLoader.loadVocabConfig(settings, null, false).getVocabs();

        assertFalse(vocabs.isEmpty());
        assertTrue(vocabs.size() <= 5);
    }

    @Test
    public void testLoadVocabsFromExplicitFilePath() throws Exception {
        Path vocabFile = Files.createTempFile("es-tok-vocab", ".txt");
        try {
            Files.writeString(vocabFile, "自然语言,10\n语言处理,9\n处理技术,8\n");

            List<String> vocabs = VocabFileLoader.loadVocabsFromFilePath(vocabFile);

            assertEquals(List.of("自然语言", "语言处理", "处理技术"), vocabs);
        } finally {
            Files.deleteIfExists(vocabFile);
        }
    }
}