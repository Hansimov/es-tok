package org.es.tok.suggest;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OwnerBackedSuggestServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    public void testSelectQueryTermsSkipsCompositeChineseDigitAliasWhenPartsExist() throws Exception {
        Method method = OwnerBackedSuggestService.class.getDeclaredMethod(
                "selectQueryTerms",
                LinkedHashSet.class,
                String.class);
        method.setAccessible(true);

        List<String> selectedTerms = (List<String>) method.invoke(
                null,
            new LinkedHashSet<>(List.of("红警08", "警08", "红警", "08")),
                "红警08");

        assertTrue(selectedTerms.toString(), selectedTerms.contains("红警"));
        assertFalse(selectedTerms.toString(), selectedTerms.contains("红警08"));
        assertFalse(selectedTerms.toString(), selectedTerms.contains("警08"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testOwnerQueryVariantsPrioritizesCompactAliasVariant() throws Exception {
        Method method = OwnerBackedSuggestService.class.getDeclaredMethod(
                "ownerQueryVariants",
                String.class);
        method.setAccessible(true);

        List<String> variants = (List<String>) method.invoke(null, "红色警戒08");

        assertEquals(List.of("红警08", "红色警戒08"), variants);
    }
}