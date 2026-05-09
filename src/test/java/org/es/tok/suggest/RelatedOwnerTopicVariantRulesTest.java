package org.es.tok.suggest;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class RelatedOwnerTopicVariantRulesTest {

    @Test
    public void testConfiguredPrefixAliasExpandsRedAlertTopic() {
        assertEquals(List.of("红警"), RelatedOwnerTopicVariantRules.variantsFor("红色警戒"));
        assertEquals(List.of("红警3"), RelatedOwnerTopicVariantRules.variantsFor("红色警戒 3"));
        assertEquals(List.of("红警二"), RelatedOwnerTopicVariantRules.variantsFor("红色警戒二"));
    }

    @Test
    public void testConfiguredPrefixAliasRejectsUnlistedHanSuffix() {
        assertEquals(List.of(), RelatedOwnerTopicVariantRules.variantsFor("红色警戒月亮3"));
    }
}
