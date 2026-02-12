package org.es.tok;

import org.es.tok.query.EsTokQueryStringQueryBuilder;
import org.es.tok.query.MatchCondition;
import org.es.tok.query.SearchConstraint;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for EsTokQueryStringQueryBuilder with constraints support.
 */
public class QueryStringBuilderTest {

    @Test
    public void testBasicConstruction() {
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test query");
        assertEquals("test query", builder.queryString());
        assertTrue(builder.constraints().isEmpty());
        assertEquals(0, builder.maxFreq());
    }

    @Test
    public void testMaxFreq() {
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test query")
                .maxFreq(100);
        assertEquals(100, builder.maxFreq());
    }

    @Test
    public void testZeroMaxFreq() {
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test")
                .maxFreq(0);
        assertEquals(0, builder.maxFreq());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullQuery() {
        String nullQuery = null;
        new EsTokQueryStringQueryBuilder(nullQuery);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeMaxFreq() {
        new EsTokQueryStringQueryBuilder("test").maxFreq(-1);
    }

    @Test
    public void testWriteableName() {
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test");
        assertEquals("es_tok_query_string", builder.getWriteableName());
    }

    // ===== Constraint tests =====

    @Test
    public void testEmptyConstraints() {
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test")
                .constraints(Collections.emptyList());
        assertTrue(builder.constraints().isEmpty());
    }

    @Test
    public void testNullConstraintsClearsToEmpty() {
        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test")
                .constraints(null);
        assertTrue(builder.constraints().isEmpty());
    }

    @Test
    public void testSingleAndConstraint() {
        MatchCondition condition = new MatchCondition(
                Arrays.asList("token1", "token2"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        SearchConstraint constraint = new SearchConstraint(
                SearchConstraint.BoolType.AND, List.of(condition));

        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test query")
                .constraints(List.of(constraint));

        assertEquals(1, builder.constraints().size());
        assertEquals(SearchConstraint.BoolType.AND, builder.constraints().get(0).getBoolType());
    }

    @Test
    public void testMultipleConstraints() {
        MatchCondition cond1 = new MatchCondition(
                Arrays.asList("必须有"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        MatchCondition cond2 = new MatchCondition(
                Collections.emptyList(),
                Arrays.asList("pre_"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        MatchCondition cond3 = new MatchCondition(
                Arrays.asList("排除词"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());

        SearchConstraint and = new SearchConstraint(SearchConstraint.BoolType.AND, List.of(cond1));
        SearchConstraint or = new SearchConstraint(SearchConstraint.BoolType.OR, List.of(cond1, cond2));
        SearchConstraint not = new SearchConstraint(SearchConstraint.BoolType.NOT, List.of(cond3));

        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test")
                .constraints(List.of(and, or, not));

        assertEquals(3, builder.constraints().size());
        assertEquals(SearchConstraint.BoolType.AND, builder.constraints().get(0).getBoolType());
        assertEquals(SearchConstraint.BoolType.OR, builder.constraints().get(1).getBoolType());
        assertEquals(SearchConstraint.BoolType.NOT, builder.constraints().get(2).getBoolType());
    }

    @Test
    public void testConstraintWithAllConditionTypes() {
        MatchCondition condition = new MatchCondition(
                Arrays.asList("exact_token"),
                Arrays.asList("pre_"),
                Arrays.asList("_suf"),
                Arrays.asList("mid"),
                Arrays.asList("^test.*$"));
        SearchConstraint constraint = new SearchConstraint(
                SearchConstraint.BoolType.AND, List.of(condition));

        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test")
                .constraints(List.of(constraint));

        MatchCondition retrieved = builder.constraints().get(0).getConditions().get(0);
        assertEquals(1, retrieved.getHaveToken().size());
        assertEquals(1, retrieved.getWithPrefixes().size());
        assertEquals(1, retrieved.getWithSuffixes().size());
        assertEquals(1, retrieved.getWithContains().size());
        assertEquals(1, retrieved.getWithPatterns().size());
    }

    // ===== Combined settings tests =====

    @Test
    public void testCombinedSettings() {
        MatchCondition condition = new MatchCondition(
                Arrays.asList("的", "了", "是"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        SearchConstraint constraint = new SearchConstraint(
                SearchConstraint.BoolType.NOT, List.of(condition));

        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("测试 查询 文档")
                .field("content")
                .defaultField("content")
                .constraints(List.of(constraint))
                .maxFreq(50)
                .lenient(true)
                .boost(2.0f);

        assertEquals("测试 查询 文档", builder.queryString());
        assertEquals(1, builder.constraints().size());
        assertEquals(50, builder.maxFreq());
        assertTrue(builder.lenient());
        assertEquals(2.0f, builder.boost(), 0.001f);
    }

    @Test
    public void testFluentAPI() {
        MatchCondition condition = new MatchCondition(
                Arrays.asList("token1"),
                Arrays.asList("pre_"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        SearchConstraint constraint = new SearchConstraint(
                SearchConstraint.BoolType.AND, List.of(condition));

        EsTokQueryStringQueryBuilder builder = new EsTokQueryStringQueryBuilder("test")
                .field("field1")
                .field("field2", 2.0f)
                .defaultField("content")
                .constraints(List.of(constraint))
                .maxFreq(100)
                .phraseSlop(2)
                .lenient(true)
                .analyzeWildcard(true)
                .boost(1.5f);

        assertEquals("test", builder.queryString());
        assertTrue(builder.fields().containsKey("field1"));
        assertTrue(builder.fields().containsKey("field2"));
        assertEquals(100, builder.maxFreq());
        assertFalse(builder.constraints().isEmpty());
    }

    // ===== Equality tests =====

    @Test
    public void testEquality() {
        MatchCondition condition = new MatchCondition(
                Arrays.asList("a", "b", "c"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        SearchConstraint constraint = new SearchConstraint(
                SearchConstraint.BoolType.AND, List.of(condition));

        EsTokQueryStringQueryBuilder builder1 = new EsTokQueryStringQueryBuilder("test query")
                .constraints(List.of(constraint))
                .maxFreq(50);

        EsTokQueryStringQueryBuilder builder2 = new EsTokQueryStringQueryBuilder("test query")
                .constraints(List.of(constraint))
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
    public void testInequalityDifferentConstraints() {
        MatchCondition cond1 = new MatchCondition(
                Arrays.asList("a"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        MatchCondition cond2 = new MatchCondition(
                Arrays.asList("b"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());

        EsTokQueryStringQueryBuilder builder1 = new EsTokQueryStringQueryBuilder("test")
                .constraints(List.of(new SearchConstraint(SearchConstraint.BoolType.AND, List.of(cond1))));

        EsTokQueryStringQueryBuilder builder2 = new EsTokQueryStringQueryBuilder("test")
                .constraints(List.of(new SearchConstraint(SearchConstraint.BoolType.AND, List.of(cond2))));

        assertNotEquals(builder1, builder2);
    }
}
