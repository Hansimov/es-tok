package org.es.tok.suggest;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PinyinSupportTest {

    @Test
    public void testPrefixMatchScoreAcceptsChineseAliasWithInterleavedAsciiSuffix() {
        assertTrue(
                PinyinSupport.prefixMatchScore("红警08", "红警HBK08") > 0.0f);
    }
}