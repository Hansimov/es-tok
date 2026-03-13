package org.es.tok.query;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for EsTokConstraintsQueryBuilder — standalone constraint query
 * designed for KNN search filter integration.
 * <p>
 * Tests cover:
 * - Basic construction and naming (es_tok_constraints)
 * - Per-constraint fields targeting
 * - Boolean constraint types (AND/OR/NOT)
 * - KNN use case scenarios
 * - Equality and immutability
 */
public class KnnConstraintTest {

        // ===== Basic construction =====

        @Test
        public void testBasicConstruction() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("token"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, List.of(cond));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                List.of("title", "tags"), List.of(constraint));

                assertEquals("es_tok_constraints", builder.getWriteableName());
                assertEquals(2, builder.fields().size());
                assertEquals(1, builder.constraints().size());
        }

        @Test(expected = IllegalArgumentException.class)
        public void testNullConstraintsThrows() {
                new EsTokConstraintsQueryBuilder(List.of("title"), null);
        }

        @Test(expected = IllegalArgumentException.class)
        public void testEmptyConstraintsThrows() {
                new EsTokConstraintsQueryBuilder(List.of("title"), Collections.emptyList());
        }

        @Test
        public void testNullFieldsDefaultsToWildcard() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("token"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, List.of(cond));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(null, List.of(constraint));
                assertEquals(List.of("*"), builder.fields());
        }

        @Test
        public void testEmptyFieldsDefaultsToWildcard() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("token"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, List.of(cond));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                Collections.emptyList(), List.of(constraint));
                assertEquals(List.of("*"), builder.fields());
        }

        // ===== Per-constraint fields =====

        @Test
        public void testPerConstraintFields() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("影视飓风"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                // Constraint with per-constraint fields
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, cond,
                                List.of("title", "tags"));

                assertTrue(constraint.hasFields());
                assertEquals(List.of("title", "tags"), constraint.getFields());

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                null, List.of(constraint));
                // Top-level fields default to ["*"]
                assertEquals(List.of("*"), builder.fields());
                // Per-constraint fields are preserved
                assertEquals(List.of("title", "tags"),
                                builder.constraints().get(0).getFields());
        }

        @Test
        public void testMixedFieldsAndDefaults() {
                MatchCondition cond1 = new MatchCondition(
                                Arrays.asList("影视飓风"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                MatchCondition cond2 = new MatchCondition(
                                Arrays.asList("广告"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());

                // Constraint with specific fields
                SearchConstraint withFields = new SearchConstraint(
                                SearchConstraint.BoolType.AND, cond1,
                                List.of("title"));
                // Constraint without specific fields (uses defaults)
                SearchConstraint useDefaults = new SearchConstraint(
                                SearchConstraint.BoolType.NOT, cond2);

                assertFalse(useDefaults.hasFields());

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                List.of("title", "tags"), List.of(withFields, useDefaults));

                assertTrue(builder.constraints().get(0).hasFields());
                assertFalse(builder.constraints().get(1).hasFields());
        }

        // ===== Constraint type tests =====

        @Test
        public void testAndConstraint() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("影视飓风"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, List.of(cond));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                List.of("title"), List.of(constraint));

                assertEquals(SearchConstraint.BoolType.AND,
                                builder.constraints().get(0).getBoolType());
        }

        @Test
        public void testNotConstraint() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("广告"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.NOT, List.of(cond));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                List.of("title", "tags"), List.of(constraint));

                assertEquals(SearchConstraint.BoolType.NOT,
                                builder.constraints().get(0).getBoolType());
        }

        @Test
        public void testOrConstraint() {
                MatchCondition cond1 = new MatchCondition(
                                Arrays.asList("科技"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                MatchCondition cond2 = new MatchCondition(
                                Collections.emptyList(),
                                Arrays.asList("深度"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.OR, List.of(cond1, cond2));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                List.of("title"), List.of(constraint));

                assertEquals(SearchConstraint.BoolType.OR,
                                builder.constraints().get(0).getBoolType());
                assertEquals(2, builder.constraints().get(0).getConditions().size());
        }

        // ===== Combined constraints =====

        @Test
        public void testMultipleConstraints() {
                // Must have "影视飓风" AND must NOT have "广告"
                MatchCondition mustHave = new MatchCondition(
                                Arrays.asList("影视飓风"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                MatchCondition mustNotHave = new MatchCondition(
                                Arrays.asList("广告"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());

                SearchConstraint andConstraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, List.of(mustHave));
                SearchConstraint notConstraint = new SearchConstraint(
                                SearchConstraint.BoolType.NOT, List.of(mustNotHave));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                List.of("title", "tags", "desc"), List.of(andConstraint, notConstraint));

                assertEquals(2, builder.constraints().size());
                assertEquals(3, builder.fields().size());
        }

        @Test
        public void testAllConditionTypes() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("exact_token"),
                                Arrays.asList("pre_"),
                                Arrays.asList("_suf"),
                                Arrays.asList("mid"),
                                Arrays.asList("^test.*$"));
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, List.of(cond));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                List.of("title"), List.of(constraint));

                MatchCondition retrieved = builder.constraints().get(0).getConditions().get(0);
                assertEquals(1, retrieved.getHaveToken().size());
                assertEquals(1, retrieved.getWithPrefixes().size());
                assertEquals(1, retrieved.getWithSuffixes().size());
                assertEquals(1, retrieved.getWithContains().size());
                assertEquals(1, retrieved.getWithPatterns().size());
        }

        // ===== Fields with boost =====

        @Test
        public void testFieldsWithBoost() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("token"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, List.of(cond));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                List.of("title^3", "tags^2", "desc"), List.of(constraint));

                assertEquals(3, builder.fields().size());
                assertTrue(builder.fields().contains("title^3"));
                assertTrue(builder.fields().contains("tags^2"));
                assertTrue(builder.fields().contains("desc"));
        }

        // ===== Equality =====

        @Test
        public void testEquality() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("a", "b"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, List.of(cond));

                EsTokConstraintsQueryBuilder builder1 = new EsTokConstraintsQueryBuilder(
                                List.of("title"), List.of(constraint));
                EsTokConstraintsQueryBuilder builder2 = new EsTokConstraintsQueryBuilder(
                                List.of("title"), List.of(constraint));

                assertEquals(builder1, builder2);
                assertEquals(builder1.hashCode(), builder2.hashCode());
        }

        @Test
        public void testInequality() {
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

                EsTokConstraintsQueryBuilder builder1 = new EsTokConstraintsQueryBuilder(
                                List.of("title"),
                                List.of(new SearchConstraint(SearchConstraint.BoolType.AND, List.of(cond1))));
                EsTokConstraintsQueryBuilder builder2 = new EsTokConstraintsQueryBuilder(
                                List.of("title"),
                                List.of(new SearchConstraint(SearchConstraint.BoolType.AND, List.of(cond2))));

                assertNotEquals(builder1, builder2);
        }

        @Test
        public void testInequalityDifferentFields() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("a"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, List.of(cond));

                EsTokConstraintsQueryBuilder builder1 = new EsTokConstraintsQueryBuilder(
                                List.of("title"), List.of(constraint));
                EsTokConstraintsQueryBuilder builder2 = new EsTokConstraintsQueryBuilder(
                                List.of("tags"), List.of(constraint));

                assertNotEquals(builder1, builder2);
        }

        @Test
        public void testInequalityDifferentConstraintFields() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("a"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint c1 = new SearchConstraint(
                                SearchConstraint.BoolType.AND, cond, List.of("title"));
                SearchConstraint c2 = new SearchConstraint(
                                SearchConstraint.BoolType.AND, cond, List.of("tags"));

                assertNotEquals(c1, c2);
        }

        // ===== Boost and query name =====

        @Test
        public void testBoost() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("token"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, List.of(cond));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                List.of("title"), List.of(constraint));
                builder.boost(2.0f);
                assertEquals(2.0f, builder.boost(), 0.001f);
        }

        @Test
        public void testQueryName() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("token"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, List.of(cond));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                List.of("title"), List.of(constraint));
                builder.queryName("my_constraint");
                assertEquals("my_constraint", builder.queryName());
        }

        // ===== Immutability =====

        @Test
        public void testFieldsReturnUnmodifiable() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("token"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, List.of(cond));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                List.of("title"), List.of(constraint));
                try {
                        builder.fields().add("extra");
                        fail("Should throw UnsupportedOperationException");
                } catch (UnsupportedOperationException e) {
                        // expected
                }
        }

        @Test
        public void testConstraintsReturnUnmodifiable() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("token"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, List.of(cond));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                List.of("title"), List.of(constraint));
                try {
                        builder.constraints().add(constraint);
                        fail("Should throw UnsupportedOperationException");
                } catch (UnsupportedOperationException e) {
                        // expected
                }
        }

        @Test
        public void testPerConstraintFieldsReturnUnmodifiable() {
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("token"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, cond, List.of("title"));

                try {
                        constraint.getFields().add("extra");
                        fail("Should throw UnsupportedOperationException");
                } catch (UnsupportedOperationException e) {
                        // expected
                }
        }

        // ===== KNN use case scenarios =====

        @Test
        public void testKnnFilterScenario_mustHaveToken() {
                // Scenario: KNN search for "影视飓风" with constraint that
                // results must contain token "影视飓风" in title or tags
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("影视飓风"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, cond,
                                List.of("title", "tags"));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                null, List.of(constraint));

                assertEquals("es_tok_constraints", builder.getWriteableName());
                assertFalse(builder.constraints().get(0).isEmpty());
                assertTrue(builder.constraints().get(0).hasFields());
        }

        @Test
        public void testKnnFilterScenario_excludeToken() {
                // Scenario: KNN search excluding documents with "广告" token
                MatchCondition cond = new MatchCondition(
                                Arrays.asList("广告", "推广"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.NOT, cond,
                                List.of("title", "tags", "desc"));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                null, List.of(constraint));

                assertEquals(SearchConstraint.BoolType.NOT,
                                builder.constraints().get(0).getBoolType());
        }

        @Test
        public void testKnnFilterScenario_combinedConstraints() {
                // Scenario: KNN search with multiple constraints:
                // - Must have prefix "深度" in title (per-constraint fields)
                // - Must NOT have exact token "广告" in any field (default fields)
                // - Should have either "科技" OR "学习" in tags (per-constraint fields)
                MatchCondition prefixCond = new MatchCondition(
                                Collections.emptyList(),
                                Arrays.asList("深度"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                MatchCondition excludeCond = new MatchCondition(
                                Arrays.asList("广告"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                MatchCondition orCond1 = new MatchCondition(
                                Arrays.asList("科技"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());
                MatchCondition orCond2 = new MatchCondition(
                                Arrays.asList("学习"),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList());

                SearchConstraint mustPrefix = new SearchConstraint(
                                SearchConstraint.BoolType.AND, prefixCond,
                                List.of("title"));
                SearchConstraint mustNotAd = new SearchConstraint(
                                SearchConstraint.BoolType.NOT, List.of(excludeCond));
                SearchConstraint eitherTopic = new SearchConstraint(
                                SearchConstraint.BoolType.OR, List.of(orCond1, orCond2),
                                List.of("tags"));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                List.of("title^3", "tags^2", "desc"),
                                List.of(mustPrefix, mustNotAd, eitherTopic));

                assertEquals(3, builder.constraints().size());
                assertEquals(3, builder.fields().size());
                // Verify per-constraint fields
                assertTrue(builder.constraints().get(0).hasFields());
                assertFalse(builder.constraints().get(1).hasFields());
                assertTrue(builder.constraints().get(2).hasFields());
        }

        @Test
        public void testKnnFilterScenario_patternConstraint() {
                // Scenario: KNN search requiring token matching a regex pattern
                MatchCondition cond = new MatchCondition(
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Arrays.asList("^v[0-9]+$"));
                SearchConstraint constraint = new SearchConstraint(
                                SearchConstraint.BoolType.AND, List.of(cond));

                EsTokConstraintsQueryBuilder builder = new EsTokConstraintsQueryBuilder(
                                List.of("title", "tags"), List.of(constraint));

                assertFalse(builder.constraints().get(0).isEmpty());
                assertEquals(1, builder.constraints().get(0).getConditions().get(0)
                                .getWithPatterns().size());
        }
}
