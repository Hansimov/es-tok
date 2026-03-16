package org.es.tok.suggest;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RelatedOwnerQueryTuningTest {

    @Test
    public void testBuildQueryPlansFallsBackToSingleSeedMatch() {
        List<RelatedOwnerQueryTuning.QueryPlan> plans = RelatedOwnerQueryTuning.buildQueryPlans(
                "我在雪山救了100只狐狸 尝试制作的邵氏ai反转短剧",
                5);

        assertEquals(List.of(3, 2, 1), plans.stream().map(RelatedOwnerQueryTuning.QueryPlan::minimumSeedMatches).toList());
    }

    @Test
    public void testWhitespaceQueryCanStillUseExpansionWhenSeedSetIsSmall() {
        assertTrue(RelatedOwnerQueryTuning.shouldExpandTopicTerms("小企鹅这是在装傻吗 终末地", 3));
    }
}