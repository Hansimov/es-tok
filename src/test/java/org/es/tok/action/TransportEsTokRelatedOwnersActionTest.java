package org.es.tok.action;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class TransportEsTokRelatedOwnersActionTest {

    @Test
    public void testSelectMergedOwnersPromotesAliasMatchedOwnerGlobally() {
        List<EsTokRelatedOwnerOption> merged = TransportEsTokRelatedOwnersAction.selectMergedOwners(
                "红警08",
                List.of(
                        new EsTokRelatedOwnerOption(75677695L, "红警V神", 19, 538160.3f, 6),
                        new EsTokRelatedOwnerOption(3546746313509613L, "陆波二号", 14, 497824.97f, 6),
                        new EsTokRelatedOwnerOption(1629347259L, "红警HBK08", 2, 392128.7f, 1)),
                8);

        assertEquals(1629347259L, merged.get(0).mid());
        assertEquals("红警HBK08", merged.get(0).name());
    }

    @Test
    public void testSelectMergedOwnersDoesNotPromoteGenericTopicQuery() {
        List<EsTokRelatedOwnerOption> merged = TransportEsTokRelatedOwnersAction.selectMergedOwners(
                "红警",
                List.of(
                        new EsTokRelatedOwnerOption(75677695L, "红警V神", 19, 538160.3f, 6),
                        new EsTokRelatedOwnerOption(1629347259L, "红警HBK08", 2, 392128.7f, 1)),
                8);

        assertEquals(75677695L, merged.get(0).mid());
        assertEquals("红警V神", merged.get(0).name());
    }
}