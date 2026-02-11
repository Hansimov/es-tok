package org.es.tok;

import org.es.tok.query.EsTokQueryStringQueryBuilder;
import org.es.tok.rules.SearchRules;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * Unit tests for EsTokQueryStringQueryBuilder
 */
public class QueryStringBuilderTest {

    @Test
    public void testBasicConstruction() {
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test query");
        assertEquals("test query", builder.queryString());
        assertTrue(builder.searchRules().isEmpty());
        assertEquals(0, builder.maxFreq());
    }

    @Test
    public void testSearchRulesExcludeTokens() {
        SearchRules rules = new SearchRules(
                Arrays.asList("token1", "token2", "token3"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test query")
                .searchRules(rules);

        assertEquals(rules, builder.searchRules());
        assertFalse(builder.searchRules().isEmpty());
        assertEquals(3, builder.searchRules().getExcludeTokens().size());
    }

    @Test
    public void testSearchRulesAllFields() {
        SearchRules rules = new SearchRules(
                Arrays.asList("的", "了"),
                Arrays.asList("pre_"),
                Arrays.asList("_suf"),
                Arrays.asList("mid"),
                Arrays.asList("^test.*$"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test query")
                .searchRules(rules);

        assertEquals(2, builder.searchRules().getExcludeTokens().size());
        assertEquals(1, builder.searchRules().getExcludePrefixes().size());
        assertEquals(1, builder.searchRules().getExcludeSuffixes().size());
        assertEquals(1, builder.searchRules().getExcludeContains().size());
        assertEquals(1, builder.searchRules().getExcludePatterns().size());
    }

    @Test
    public void testMaxFreq() {
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test query")
                .maxFreq(100);

        assertEquals(100, builder.maxFreq());
    }

    @Test
    public void testCombinedSettings() {
        SearchRules rules = new SearchRules(
                Arrays.asList("的", "了", "是"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("测试 查询 文档")
                .field("content")
                .defaultField("content")
                .searchRules(rules)
                .maxFreq(50)
                .lenient(true)
                .boost(2.0f);

        assertEquals("测试 查询 文档", builder.queryString());
        assertEquals(rules, builder.searchRules());
        assertEquals(50, builder.maxFreq());
        assertTrue(builder.lenient());
        assertEquals(2.0f, builder.boost(), 0.001f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullQuery() {
        String nullQuery = null;
        new EsTokQueryStringQueryBuilder(nullQuery);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeMaxFreq() {
        new EsTokQueryStringQueryBuilder("test")
                .maxFreq(-1);
    }

    @Test
    public void testEmptySearchRules() {
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test")
                .searchRules(SearchRules.EMPTY);

        assertTrue(builder.searchRules().isEmpty());
    }

    @Test
    public void testZeroMaxFreq() {
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test")
                .maxFreq(0);

        assertEquals(0, builder.maxFreq());
    }

    @Test
    public void testWriteableName() {
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test");
        assertEquals("es_tok_query_string", builder.getWriteableName());
    }

    @Test
    public void testEquality() {
        SearchRules rules = new SearchRules(
                Arrays.asList("a", "b", "c"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        EsTokQueryStringQueryBuilder builder1 = new EsTokQueryStringQueryBuilder("test query")
                .searchRules(rules)
                .maxFreq(50);

        EsTokQueryStringQueryBuilder builder2 = new EsTokQueryStringQueryBuilder("test query")
                .searchRules(rules)
                .maxFreq(50);

        assertEquals(builder1, builder2);
        assertEquals(builder1.hashCode(), builder2.hashCode());
    }

    @Test
    public void testInequality() {
        EsTokQueryStringQueryBuilder builder1 = new EsTokQueryStringQueryBuilder("test query 1")
                .maxFreq(50);

        EsTokQueryStringQueryBuilder builder2 = new EsTokQueryStringQueryBuilder("test query 2")
                .maxFreq(100);

        assertNotEquals(builder1, builder2);
    }

    @Test
    public void testInequalityDifferentRules() {
        SearchRules rules1 = new SearchRules(
                Arrays.asList("a"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        SearchRules rules2 = new SearchRules(
                Arrays.asList("b"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());

        EsTokQueryStringQueryBuilder builder1 = new EsTokQueryStringQueryBuilder("test")
                .searchRules(rules1);

        EsTokQueryStringQueryBuilder builder2 = new EsTokQueryStringQueryBuilder("test")
                .searchRules(rules2);

        assertNotEquals(builder1, builder2);
    }

    @Test
    public void testFluentAPI() {
        SearchRules rules = new SearchRules(
                Arrays.asList("token1"),
                Arrays.asList("pre_"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList());
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test")
                .field("field1")
                .field("field2", 2.0f)
                .defaultField("content")
                .searchRules(rules)
                .maxFreq(100)
                .phraseSlop(2)
                .lenient(true)
                .analyzeWildcard(true)
                .boost(1.5f);

        assertEquals("test", builder.queryString());
        assertTrue(builder.fields().containsKey("field1"));
        assertTrue(builder.fields().containsKey("field2"));
        assertEquals(100, builder.maxFreq());
        assertFalse(builder.searchRules().isEmpty());
    }
}
