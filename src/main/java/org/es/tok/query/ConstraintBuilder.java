package org.es.tok.query;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.RegexpQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.WildcardQuery;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Utility class for parsing search constraints from XContent and building
 * Lucene queries from constraint definitions.
 * <p>
 * Constraints allow boolean-logic filtering on document tokens during search:
 * <ul>
 * <li>{@code AND} — document MUST have a matching token</li>
 * <li>{@code OR} — document must match at least one sub-condition</li>
 * <li>{@code NOT} — document MUST NOT have a matching token</li>
 * </ul>
 */
public class ConstraintBuilder {

    // Field names for constraint conditions
    private static final String HAVE_TOKEN = "have_token";
    private static final String WITH_PREFIXES = "with_prefixes";
    private static final String WITH_SUFFIXES = "with_suffixes";
    private static final String WITH_CONTAINS = "with_contains";
    private static final String WITH_PATTERNS = "with_patterns";

    // Per-constraint field targeting
    private static final String FIELDS = "fields";

    // Boolean operators
    private static final String AND = "AND";
    private static final String OR = "OR";
    private static final String NOT = "NOT";

    /**
     * Parse constraints array from XContentParser.
     * <p>
     * Expected format: array of constraint objects.
     * Each object can be:
     * <ul>
     * <li>A bare MatchCondition (shorthand for AND): {@code {"have_token":
     * [...]}}</li>
     * <li>An AND constraint: {@code {"AND": {"have_token": [...]}}}</li>
     * <li>An OR constraint: {@code {"OR": [{"have_token": [...]}, {"with_prefixes":
     * [...]}]}}</li>
     * <li>A NOT constraint: {@code {"NOT": {"have_token": [...]}}}</li>
     * </ul>
     */
    public static List<SearchConstraint> parseConstraints(XContentParser parser) throws IOException {
        List<SearchConstraint> constraints = new ArrayList<>();

        // Expect START_ARRAY
        XContentParser.Token token = parser.currentToken();
        if (token != XContentParser.Token.START_ARRAY) {
            throw new ParsingException(parser.getTokenLocation(),
                    "[constraints] must be an array");
        }

        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
            if (token == XContentParser.Token.START_OBJECT) {
                constraints.add(parseConstraintObject(parser));
            } else {
                throw new ParsingException(parser.getTokenLocation(),
                        "[constraints] array elements must be objects");
            }
        }

        return constraints;
    }

    /**
     * Parse a single constraint object from the parser.
     * <p>
     * Each constraint object can optionally include a {@code "fields"} array
     * to specify per-constraint field targeting.
     */
    private static SearchConstraint parseConstraintObject(XContentParser parser) throws IOException {
        String currentFieldName = null;
        XContentParser.Token token;

        // Collect all fields in this object to determine the type
        List<String> haveToken = new ArrayList<>();
        List<String> withPrefixes = new ArrayList<>();
        List<String> withSuffixes = new ArrayList<>();
        List<String> withContains = new ArrayList<>();
        List<String> withPatterns = new ArrayList<>();
        List<String> constraintFields = new ArrayList<>();

        SearchConstraint.BoolType boolType = null;
        SearchConstraint result = null;

        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (AND.equals(currentFieldName)) {
                // {"AND": {condition}} — single condition
                if (token == XContentParser.Token.START_OBJECT) {
                    MatchCondition cond = parseMatchConditionObject(parser);
                    result = new SearchConstraint(SearchConstraint.BoolType.AND, cond);
                } else {
                    throw new ParsingException(parser.getTokenLocation(),
                            "[AND] value must be an object");
                }
            } else if (OR.equals(currentFieldName)) {
                // {"OR": [{condition}, ...]} — array of conditions
                // Also supports: {"OR": {condition}} — single condition
                if (token == XContentParser.Token.START_ARRAY) {
                    List<MatchCondition> conditions = new ArrayList<>();
                    while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                        if (token == XContentParser.Token.START_OBJECT) {
                            conditions.add(parseMatchConditionObject(parser));
                        }
                    }
                    result = new SearchConstraint(SearchConstraint.BoolType.OR, conditions);
                } else if (token == XContentParser.Token.START_OBJECT) {
                    MatchCondition cond = parseMatchConditionObject(parser);
                    result = new SearchConstraint(SearchConstraint.BoolType.OR, cond);
                } else {
                    throw new ParsingException(parser.getTokenLocation(),
                            "[OR] value must be an array or object");
                }
            } else if (NOT.equals(currentFieldName)) {
                // {"NOT": {condition}} — single condition
                if (token == XContentParser.Token.START_OBJECT) {
                    MatchCondition cond = parseMatchConditionObject(parser);
                    result = new SearchConstraint(SearchConstraint.BoolType.NOT, cond);
                } else {
                    throw new ParsingException(parser.getTokenLocation(),
                            "[NOT] value must be an object");
                }
            } else if (FIELDS.equals(currentFieldName)) {
                // Per-constraint field targeting: "fields": ["title", "tags^2"]
                if (token == XContentParser.Token.START_ARRAY) {
                    constraintFields.addAll(parseStringArray(parser));
                } else {
                    throw new ParsingException(parser.getTokenLocation(),
                            "[fields] value must be an array");
                }
            } else if (token == XContentParser.Token.START_ARRAY) {
                // Bare condition field (shorthand for AND)
                List<String> list = parseStringArray(parser);
                switch (currentFieldName) {
                    case HAVE_TOKEN -> haveToken.addAll(list);
                    case WITH_PREFIXES -> withPrefixes.addAll(list);
                    case WITH_SUFFIXES -> withSuffixes.addAll(list);
                    case WITH_CONTAINS -> withContains.addAll(list);
                    case WITH_PATTERNS -> withPatterns.addAll(list);
                    default -> throw new ParsingException(parser.getTokenLocation(),
                            "[constraints] does not support field [" + currentFieldName + "]");
                }
                boolType = SearchConstraint.BoolType.AND; // bare condition = AND
            } else {
                throw new ParsingException(parser.getTokenLocation(),
                        "[constraints] unexpected token in constraint object");
            }
        }

        // Apply per-constraint fields
        List<String> fields = constraintFields.isEmpty() ? null : constraintFields;

        // If we found a boolean wrapper (AND/OR/NOT), apply fields and return
        if (result != null) {
            if (fields != null) {
                // Re-create with fields
                return new SearchConstraint(result.getBoolType(), result.getConditions(), fields);
            }
            return result;
        }

        // Otherwise, build from bare condition fields
        MatchCondition condition = new MatchCondition(haveToken, withPrefixes,
                withSuffixes, withContains, withPatterns);
        return new SearchConstraint(
                boolType != null ? boolType : SearchConstraint.BoolType.AND,
                condition, fields);
    }

    /**
     * Parse a MatchCondition from an object.
     */
    private static MatchCondition parseMatchConditionObject(XContentParser parser) throws IOException {
        List<String> haveToken = new ArrayList<>();
        List<String> withPrefixes = new ArrayList<>();
        List<String> withSuffixes = new ArrayList<>();
        List<String> withContains = new ArrayList<>();
        List<String> withPatterns = new ArrayList<>();

        String currentFieldName = null;
        XContentParser.Token token;

        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_ARRAY) {
                List<String> list = parseStringArray(parser);
                switch (currentFieldName) {
                    case HAVE_TOKEN -> haveToken.addAll(list);
                    case WITH_PREFIXES -> withPrefixes.addAll(list);
                    case WITH_SUFFIXES -> withSuffixes.addAll(list);
                    case WITH_CONTAINS -> withContains.addAll(list);
                    case WITH_PATTERNS -> withPatterns.addAll(list);
                    default -> throw new ParsingException(parser.getTokenLocation(),
                            "[constraints] condition does not support field [" + currentFieldName + "]");
                }
            }
        }

        return new MatchCondition(haveToken, withPrefixes, withSuffixes, withContains, withPatterns);
    }

    /**
     * Parse a string array from the parser (positioned after START_ARRAY).
     */
    private static List<String> parseStringArray(XContentParser parser) throws IOException {
        List<String> list = new ArrayList<>();
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
            if (token.isValue()) {
                list.add(parser.text());
            }
        }
        return list;
    }

    // ========================================================================
    // Lucene Query Building
    // ========================================================================

    /**
     * Build a Lucene query that wraps the original query with constraint clauses.
     * <p>
     * Each constraint uses its own {@code fields} if specified, otherwise
     * falls back to {@code defaultFields}.
     *
     * @param originalQuery the base query from query_string parsing
     * @param constraints   the list of constraints to apply
     * @param defaultFields the default fields to check constraints against
     *                      (used when a constraint has no per-constraint fields)
     * @return a BooleanQuery combining the original query with constraint clauses
     */
    public static Query buildConstrainedQuery(Query originalQuery,
            List<SearchConstraint> constraints,
            List<String> defaultFields) {
        if (constraints == null || constraints.isEmpty()) {
            return originalQuery;
        }

        // Filter out empty constraints
        List<SearchConstraint> activeConstraints = constraints.stream()
                .filter(c -> !c.isEmpty())
                .toList();
        if (activeConstraints.isEmpty()) {
            return originalQuery;
        }

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(originalQuery, BooleanClause.Occur.MUST);

        for (SearchConstraint constraint : activeConstraints) {
            // Resolve fields: per-constraint fields take priority over defaults
            List<String> fields = constraint.hasFields()
                    ? constraint.getFields()
                    : defaultFields;
            Query constraintQuery = buildConstraintQuery(constraint, fields);
            if (constraintQuery != null) {
                switch (constraint.getBoolType()) {
                    case AND -> builder.add(constraintQuery, BooleanClause.Occur.MUST);
                    case OR -> builder.add(constraintQuery, BooleanClause.Occur.MUST);
                    case NOT -> builder.add(constraintQuery, BooleanClause.Occur.MUST_NOT);
                }
            }
        }

        return builder.build();
    }

    /**
     * Build a Lucene query for a single constraint.
     */
    private static Query buildConstraintQuery(SearchConstraint constraint, List<String> fields) {
        List<MatchCondition> conditions = constraint.getConditions();
        if (conditions.isEmpty()) {
            return null;
        }

        if (constraint.getBoolType() == SearchConstraint.BoolType.OR && conditions.size() > 1) {
            // OR: any condition must match
            BooleanQuery.Builder orBuilder = new BooleanQuery.Builder();
            for (MatchCondition cond : conditions) {
                Query condQuery = buildConditionQuery(cond, fields);
                if (condQuery != null) {
                    orBuilder.add(condQuery, BooleanClause.Occur.SHOULD);
                }
            }
            return orBuilder.build();
        }

        // AND or NOT or single-condition OR: use the first/only condition
        if (!conditions.isEmpty()) {
            return buildConditionQuery(conditions.get(0), fields);
        }

        return null;
    }

    /**
     * Build a Lucene query for a single MatchCondition across all specified fields.
     * All rules within a condition are OR'd: any match satisfies the condition.
     */
    private static Query buildConditionQuery(MatchCondition condition, List<String> fields) {
        if (condition.isEmpty()) {
            return null;
        }

        // Build per-field queries and OR them across fields
        if (fields.size() == 1) {
            return buildConditionQueryForField(condition, fields.get(0));
        }

        BooleanQuery.Builder fieldOrBuilder = new BooleanQuery.Builder();
        for (String field : fields) {
            Query fieldQuery = buildConditionQueryForField(condition, field);
            if (fieldQuery != null) {
                fieldOrBuilder.add(fieldQuery, BooleanClause.Occur.SHOULD);
            }
        }
        return fieldOrBuilder.build();
    }

    /**
     * Build a Lucene query for a single MatchCondition on a single field.
     * All matching rules are OR'd together.
     */
    private static Query buildConditionQueryForField(MatchCondition condition, String field) {
        // Strip boost suffix (e.g., "title^3" → "title")
        String cleanField = stripBoost(field);

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        boolean hasAny = false;

        // have_token → TermQuery
        for (String token : condition.getHaveToken()) {
            builder.add(new TermQuery(new Term(cleanField, token)), BooleanClause.Occur.SHOULD);
            hasAny = true;
        }

        // with_prefixes → PrefixQuery
        for (String prefix : condition.getWithPrefixes()) {
            builder.add(new PrefixQuery(new Term(cleanField, prefix)), BooleanClause.Occur.SHOULD);
            hasAny = true;
        }

        // with_suffixes → WildcardQuery("*suffix")
        for (String suffix : condition.getWithSuffixes()) {
            builder.add(new WildcardQuery(new Term(cleanField, "*" + suffix)),
                    BooleanClause.Occur.SHOULD);
            hasAny = true;
        }

        // with_contains → WildcardQuery("*sub*")
        for (String sub : condition.getWithContains()) {
            builder.add(new WildcardQuery(new Term(cleanField, "*" + sub + "*")),
                    BooleanClause.Occur.SHOULD);
            hasAny = true;
        }

        // with_patterns → RegexpQuery
        for (String pattern : condition.getWithPatterns()) {
            try {
                builder.add(new RegexpQuery(new Term(cleanField, pattern)),
                        BooleanClause.Occur.SHOULD);
                hasAny = true;
            } catch (Exception e) {
                // Skip invalid patterns
            }
        }

        if (!hasAny) {
            return null;
        }

        return builder.build();
    }

    /**
     * Strip boost suffix from field name (e.g., "title^3" → "title").
     */
    public static String stripBoost(String field) {
        int boostIdx = field.indexOf('^');
        return boostIdx > 0 ? field.substring(0, boostIdx) : field;
    }

    // ========================================================================
    // Serialization helpers
    // ========================================================================

    /**
     * Serialize constraints to a list of maps for XContent/Stream serialization.
     */
    public static List<Map<String, Object>> constraintsToMaps(List<SearchConstraint> constraints) {
        if (constraints == null || constraints.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (SearchConstraint constraint : constraints) {
            result.add(constraintToMap(constraint));
        }
        return result;
    }

    private static Map<String, Object> constraintToMap(SearchConstraint constraint) {
        List<MatchCondition> conditions = constraint.getConditions();
        Map<String, Object> result;

        switch (constraint.getBoolType()) {
            case AND -> {
                if (conditions.size() == 1) {
                    // Can be serialized as bare condition or {"AND": condition}
                    result = new java.util.LinkedHashMap<>(conditionToMap(conditions.get(0)));
                } else {
                    result = new java.util.LinkedHashMap<>();
                    result.put(AND, conditions.stream().map(ConstraintBuilder::conditionToMap).toList());
                }
            }
            case OR -> {
                result = new java.util.LinkedHashMap<>();
                if (conditions.size() == 1) {
                    result.put(OR, conditionToMap(conditions.get(0)));
                } else {
                    result.put(OR, conditions.stream().map(ConstraintBuilder::conditionToMap).toList());
                }
            }
            case NOT -> {
                result = new java.util.LinkedHashMap<>();
                if (!conditions.isEmpty()) {
                    result.put(NOT, conditionToMap(conditions.get(0)));
                }
            }
            default -> {
                return Collections.emptyMap();
            }
        }

        // Add per-constraint fields if present
        if (constraint.hasFields()) {
            result.put(FIELDS, constraint.getFields());
        }

        return result;
    }

    private static Map<String, Object> conditionToMap(MatchCondition condition) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        if (!condition.getHaveToken().isEmpty())
            map.put(HAVE_TOKEN, condition.getHaveToken());
        if (!condition.getWithPrefixes().isEmpty())
            map.put(WITH_PREFIXES, condition.getWithPrefixes());
        if (!condition.getWithSuffixes().isEmpty())
            map.put(WITH_SUFFIXES, condition.getWithSuffixes());
        if (!condition.getWithContains().isEmpty())
            map.put(WITH_CONTAINS, condition.getWithContains());
        if (!condition.getWithPatterns().isEmpty())
            map.put(WITH_PATTERNS, condition.getWithPatterns());
        return map;
    }
}
