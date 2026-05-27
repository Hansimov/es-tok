package org.es.tok.suggest;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class RelatedOwnerTopicVariantRulesTest {

    @Test
    public void testConfiguredCompoundAbbreviationExpandsFourHanTopic() {
        assertEquals(List.of("甲丙"), RelatedOwnerTopicVariantRules.variantsFor("甲乙丙丁"));
        assertEquals(List.of("甲丙3"), RelatedOwnerTopicVariantRules.variantsFor("甲乙丙丁 3"));
        assertEquals(List.of("甲丙二"), RelatedOwnerTopicVariantRules.variantsFor("甲乙丙丁二"));
    }

    @Test
    public void testConfiguredCompoundAbbreviationRejectsUnlistedHanSuffix() {
        assertEquals(List.of(), RelatedOwnerTopicVariantRules.variantsFor("甲乙丙丁戊己"));
    }

    @Test
    public void testConfiguredSuffixAliasStripsRoleSuffix() {
        assertEquals(List.of("甲乙切片"), RelatedOwnerTopicVariantRules.variantsFor("甲乙切片员"));
        assertEquals(List.of("甲乙剪辑"), RelatedOwnerTopicVariantRules.variantsFor("甲乙剪辑号"));
    }
}
