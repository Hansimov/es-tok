package org.es.tok.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a search constraint with boolean logic (AND/OR/NOT) and
 * optional per-constraint field targeting.
 * <p>
 * Constraints filter documents based on whether their indexed tokens
 * satisfy certain conditions. Each constraint has a boolean type:
 * <ul>
 * <li>{@code AND} — document MUST have at least one token matching the
 * condition</li>
 * <li>{@code OR} — document must match at least one of the sub-conditions
 * (only valid when multiple conditions are provided)</li>
 * <li>{@code NOT} — document MUST NOT have any token matching the
 * condition</li>
 * </ul>
 * <p>
 * If no boolean type is specified, the default is AND.
 * <p>
 * Each constraint can optionally specify {@code fields} to target specific
 * index fields. When {@code fields} is empty/null, the constraint uses the
 * default fields provided by the enclosing query context.
 * <p>
 * Multiple constraint items in a constraints list combine with top-level AND
 * logic.
 * <p>
 * Examples:
 * 
 * <pre>
 * // Must have token "影视飓风" in title or tags
 * {"have_token": ["影视飓风"], "fields": ["title", "tags"]}
 *
 * // Must NOT have "广告" (uses default fields)
 * {"NOT": {"have_token": ["广告"]}}
 *
 * // OR constraint with per-constraint fields
 * {"OR": [{"have_token": ["科技"]}, {"with_prefixes": ["深度"]}],
 *  "fields": ["title^3", "tags"]}
 * </pre>
 */
public class SearchConstraint {

    /**
     * Boolean type for constraint evaluation.
     */
    public enum BoolType {
        AND, OR, NOT
    }

    private final BoolType boolType;

    /**
     * The match condition(s). For AND and NOT, typically one condition.
     * For OR, multiple conditions where any match satisfies the constraint.
     */
    private final List<MatchCondition> conditions;

    /**
     * Optional per-constraint fields. When non-empty, overrides the default
     * fields from the enclosing query context. Supports boost syntax
     * (e.g., "title^3").
     */
    private final List<String> fields;

    /**
     * Create a constraint with a single condition (no per-constraint fields).
     */
    public SearchConstraint(BoolType boolType, MatchCondition condition) {
        this(boolType, condition, null);
    }

    /**
     * Create a constraint with a single condition and optional fields.
     */
    public SearchConstraint(BoolType boolType, MatchCondition condition, List<String> fields) {
        this.boolType = boolType != null ? boolType : BoolType.AND;
        this.conditions = Collections.singletonList(
                condition != null ? condition : MatchCondition.EMPTY);
        this.fields = fields != null ? new ArrayList<>(fields) : Collections.emptyList();
    }

    /**
     * Create a constraint with multiple conditions (no per-constraint fields).
     */
    public SearchConstraint(BoolType boolType, List<MatchCondition> conditions) {
        this(boolType, conditions, null);
    }

    /**
     * Create a constraint with multiple conditions and optional fields.
     */
    public SearchConstraint(BoolType boolType, List<MatchCondition> conditions, List<String> fields) {
        this.boolType = boolType != null ? boolType : BoolType.AND;
        this.conditions = conditions != null ? new ArrayList<>(conditions) : Collections.emptyList();
        this.fields = fields != null ? new ArrayList<>(fields) : Collections.emptyList();
    }

    public BoolType getBoolType() {
        return boolType;
    }

    public List<MatchCondition> getConditions() {
        return Collections.unmodifiableList(conditions);
    }

    /**
     * Get the per-constraint fields. Empty list means "use default fields".
     */
    public List<String> getFields() {
        return Collections.unmodifiableList(fields);
    }

    /**
     * @return true if per-constraint fields are specified
     */
    public boolean hasFields() {
        return !fields.isEmpty();
    }

    /**
     * @return true if no meaningful conditions are defined
     */
    public boolean isEmpty() {
        return conditions.isEmpty() || conditions.stream().allMatch(MatchCondition::isEmpty);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        SearchConstraint that = (SearchConstraint) o;
        return boolType == that.boolType
                && Objects.equals(conditions, that.conditions)
                && Objects.equals(fields, that.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(boolType, conditions, fields);
    }

    @Override
    public String toString() {
        if (fields.isEmpty()) {
            return String.format("SearchConstraint{%s: %s}", boolType, conditions);
        }
        return String.format("SearchConstraint{%s: %s, fields=%s}", boolType, conditions, fields);
    }
}
