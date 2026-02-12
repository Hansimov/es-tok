package org.es.tok;

import org.es.tok.query.ConstraintBuilder;
import org.es.tok.query.MatchCondition;
import org.es.tok.query.SearchConstraint;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for MatchCondition, SearchConstraint, and ConstraintBuilder.
 */
public class ConstraintTest {

    // ===== MatchCondition tests =====

    @Test
    public void testEmptyCondition() {
        MatchCondition cond = new MatchCondition(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        assertTrue(cond.isEmpty());
        assertFalse(cond.matches("anything"));
    }

    @Test
    public void testHaveToken() {
        MatchCondition cond = new MatchCondition(
                Arrays.asList("hello", "world"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        assertFalse(cond.isEmpty());
        assertTrue(cond.matches("hello"));
        assertTrue(cond.matches("world"));
        assertFalse(cond.matches("foo"));
        assertFalse(cond.matches("HELLO")); // case-sensitive
    }

    @Test
    public void testWithPrefixes() {
        MatchCondition cond = new MatchCondition(
                Collections.emptyList(),
                Arrays.asList("pre_", "test_"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        assertTrue(cond.matches("pre_hello"));
        assertTrue(cond.matches("pre_"));
        assertTrue(cond.matches("test_case"));
        assertFalse(cond.matches("hello_pre_"));
        assertFalse(cond.matches("hello"));
    }

    @Test
    public void testWithSuffixes() {
        MatchCondition cond = new MatchCondition(
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("_end", "_test"),
                Collections.emptyList(),
                Collections.emptyList());
        assertTrue(cond.matches("hello_end"));
        assertTrue(cond.matches("_end"));
        assertTrue(cond.matches("my_test"));
        assertFalse(cond.matches("_end_more"));
        assertFalse(cond.matches("hello"));
    }

    @Test
    public void testWithContains() {
        MatchCondition cond = new MatchCondition(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("bad", "evil"),
                Collections.emptyList());
        assertTrue(cond.matches("this_is_bad"));
        assertTrue(cond.matches("bad"));
        assertTrue(cond.matches("evil_thing"));
        assertTrue(cond.matches("the_bad_word"));
        assertFalse(cond.matches("good"));
    }

    @Test
    public void testWithPatterns() {
        MatchCondition cond = new MatchCondition(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList("^test.*$", "^[0-9]+$"));
        assertTrue(cond.matches("test123"));
        assertTrue(cond.matches("testing"));
        assertTrue(cond.matches("12345"));
        assertFalse(cond.matches("mytest"));
        assertFalse(cond.matches("abc"));
    }

    @Test
    public void testCombinedCondition() {
        // Any matching rule should return true (OR logic within a condition)
        MatchCondition cond = new MatchCondition(
                Arrays.asList("exact"),
                Arrays.asList("pre_"),
                Arrays.asList("_end"),
                Arrays.asList("mid"),
                Collections.emptyList());
        assertTrue(cond.matches("exact"));
        assertTrue(cond.matches("pre_hello"));
        assertTrue(cond.matches("hello_end"));
        assertTrue(cond.matches("has_mid_dle"));
        assertFalse(cond.matches("nothing"));
    }

    @Test
    public void testConditionEquality() {
        MatchCondition cond1 = new MatchCondition(
                Arrays.asList("a", "b"),
                Arrays.asList("pre_"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        MatchCondition cond2 = new MatchCondition(
                Arrays.asList("a", "b"),
                Arrays.asList("pre_"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        assertEquals(cond1, cond2);
        assertEquals(cond1.hashCode(), cond2.hashCode());
    }

    @Test
    public void testConditionInequality() {
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
        assertNotEquals(cond1, cond2);
    }

    @Test
    public void testConditionGettersReturnUnmodifiable() {
        MatchCondition cond = new MatchCondition(
                Arrays.asList("a"),
                Arrays.asList("b"),
                Arrays.asList("c"),
                Arrays.asList("d"),
                Arrays.asList("e"));
        try {
            cond.getHaveToken().add("x");
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    // ===== SearchConstraint tests =====

    @Test
    public void testAndConstraint() {
        MatchCondition cond = new MatchCondition(
                Arrays.asList("token"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        SearchConstraint constraint = new SearchConstraint(
                SearchConstraint.BoolType.AND, List.of(cond));
        assertEquals(SearchConstraint.BoolType.AND, constraint.getBoolType());
        assertEquals(1, constraint.getConditions().size());
        assertFalse(constraint.isEmpty());
    }

    @Test
    public void testOrConstraint() {
        MatchCondition cond1 = new MatchCondition(
                Arrays.asList("token1"),
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
        SearchConstraint constraint = new SearchConstraint(
                SearchConstraint.BoolType.OR, List.of(cond1, cond2));
        assertEquals(SearchConstraint.BoolType.OR, constraint.getBoolType());
        assertEquals(2, constraint.getConditions().size());
    }

    @Test
    public void testNotConstraint() {
        MatchCondition cond = new MatchCondition(
                Arrays.asList("excluded"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        SearchConstraint constraint = new SearchConstraint(
                SearchConstraint.BoolType.NOT, List.of(cond));
        assertEquals(SearchConstraint.BoolType.NOT, constraint.getBoolType());
    }

    @Test
    public void testEmptyConstraint() {
        SearchConstraint constraint = new SearchConstraint(
                SearchConstraint.BoolType.AND, Collections.emptyList());
        assertTrue(constraint.isEmpty());
    }

    @Test
    public void testConstraintEquality() {
        MatchCondition cond = new MatchCondition(
                Arrays.asList("a"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        SearchConstraint c1 = new SearchConstraint(SearchConstraint.BoolType.AND, List.of(cond));
        SearchConstraint c2 = new SearchConstraint(SearchConstraint.BoolType.AND, List.of(cond));
        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());
    }

    @Test
    public void testConstraintInequalityBoolType() {
        MatchCondition cond = new MatchCondition(
                Arrays.asList("a"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        SearchConstraint c1 = new SearchConstraint(SearchConstraint.BoolType.AND, List.of(cond));
        SearchConstraint c2 = new SearchConstraint(SearchConstraint.BoolType.NOT, List.of(cond));
        assertNotEquals(c1, c2);
    }

    // ===== ConstraintBuilder serialization tests =====

    @Test
    public void testConstraintsToMaps_empty() {
        List<Map<String, Object>> maps = ConstraintBuilder.constraintsToMaps(Collections.emptyList());
        assertTrue(maps.isEmpty());
    }

    @Test
    public void testConstraintsToMaps_andConstraintSingle() {
        // Single AND condition serializes as a bare condition (no "and" wrapper)
        MatchCondition cond = new MatchCondition(
                Arrays.asList("exact_token"),
                Arrays.asList("pre_"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        SearchConstraint constraint = new SearchConstraint(
                SearchConstraint.BoolType.AND, List.of(cond));

        List<Map<String, Object>> maps = ConstraintBuilder.constraintsToMaps(List.of(constraint));
        assertEquals(1, maps.size());
        // Bare condition: keys are the condition fields directly
        assertTrue(maps.get(0).containsKey("have_token"));
        assertTrue(maps.get(0).containsKey("with_prefixes"));
    }

    @Test
    public void testConstraintsToMaps_notConstraint() {
        MatchCondition cond = new MatchCondition(
                Arrays.asList("excluded"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        SearchConstraint constraint = new SearchConstraint(
                SearchConstraint.BoolType.NOT, List.of(cond));

        List<Map<String, Object>> maps = ConstraintBuilder.constraintsToMaps(List.of(constraint));
        assertEquals(1, maps.size());
        assertTrue(maps.get(0).containsKey("NOT"));
    }

    @Test
    public void testConstraintsToMaps_orWithMultipleConditions() {
        MatchCondition cond1 = new MatchCondition(
                Arrays.asList("token1"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        MatchCondition cond2 = new MatchCondition(
                Collections.emptyList(),
                Arrays.asList("prefix_"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        SearchConstraint constraint = new SearchConstraint(
                SearchConstraint.BoolType.OR, List.of(cond1, cond2));

        List<Map<String, Object>> maps = ConstraintBuilder.constraintsToMaps(List.of(constraint));
        assertEquals(1, maps.size());
        assertTrue(maps.get(0).containsKey("OR"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orList = (List<Map<String, Object>>) maps.get(0).get("OR");
        assertEquals(2, orList.size());
    }

    @Test
    public void testStripBoost() {
        assertEquals("content", ConstraintBuilder.stripBoost("content^2.0"));
        assertEquals("title", ConstraintBuilder.stripBoost("title^1.5"));
        assertEquals("field", ConstraintBuilder.stripBoost("field"));
        assertEquals("*", ConstraintBuilder.stripBoost("*"));
    }

    // ===== Chinese token constraint tests =====

    @Test
    public void testChineseTokenMatching() {
        MatchCondition cond = new MatchCondition(
                Arrays.asList("的", "了", "是"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        assertTrue(cond.matches("的"));
        assertTrue(cond.matches("了"));
        assertTrue(cond.matches("是"));
        assertFalse(cond.matches("不"));
        assertFalse(cond.matches("的了"));
    }

    @Test
    public void testChinesePrefixMatching() {
        MatchCondition cond = new MatchCondition(
                Collections.emptyList(),
                Arrays.asList("中"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        assertTrue(cond.matches("中国"));
        assertTrue(cond.matches("中文"));
        assertFalse(cond.matches("华中"));
    }
}
