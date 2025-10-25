package org.es.tok;

import org.es.tok.query.EsTokQueryStringQueryBuilder;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for EsTokQueryStringQueryBuilder
 */
public class QueryStringBuilderTest {

    @Test
    public void testBasicConstruction() {
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test query");
        assertEquals("test query", builder.queryString());
        assertTrue(builder.ignoredTokens().isEmpty());
        assertEquals(0, builder.maxFreq());
    }

    @Test
    public void testIgnoredTokens() {
        List<String> ignored = Arrays.asList("token1", "token2", "token3");
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test query")
                .ignoredTokens(ignored);

        assertEquals(ignored, builder.ignoredTokens());
    }

    @Test
    public void testMaxFreq() {
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test query")
                .maxFreq(100);

        assertEquals(100, builder.maxFreq());
    }

    @Test
    public void testCombinedSettings() {
        List<String> ignored = Arrays.asList("的", "了", "是");
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("测试 查询 文档")
                .field("content")
                .defaultField("content")
                .ignoredTokens(ignored)
                .maxFreq(50)
                .lenient(true)
                .boost(2.0f);

        assertEquals("测试 查询 文档", builder.queryString());
        assertEquals(ignored, builder.ignoredTokens());
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

    @Test(expected = IllegalArgumentException.class)
    public void testNullIgnoredTokens() {
        new EsTokQueryStringQueryBuilder("test")
                .ignoredTokens(null);
    }

    @Test
    public void testEmptyIgnoredTokens() {
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test")
                .ignoredTokens(Arrays.asList());

        assertTrue(builder.ignoredTokens().isEmpty());
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
        List<String> ignored = Arrays.asList("a", "b", "c");

        EsTokQueryStringQueryBuilder builder1 = new EsTokQueryStringQueryBuilder("test query")
                .ignoredTokens(ignored)
                .maxFreq(50);

        EsTokQueryStringQueryBuilder builder2 = new EsTokQueryStringQueryBuilder("test query")
                .ignoredTokens(ignored)
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
    public void testFluentAPI() {
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test")
                .field("field1")
                .field("field2", 2.0f)
                .defaultField("content")
                .ignoredTokens(Arrays.asList("token1"))
                .maxFreq(100)
                .phraseSlop(2)
                .lenient(true)
                .analyzeWildcard(true)
                .boost(1.5f);

        assertEquals("test", builder.queryString());
        assertTrue(builder.fields().containsKey("field1"));
        assertTrue(builder.fields().containsKey("field2"));
        assertEquals(100, builder.maxFreq());
        assertFalse(builder.ignoredTokens().isEmpty());
    }
}
