package org.es.tok.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a search constraint with boolean logic (AND/OR/NOT).
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
 * Multiple constraint items in a constraints list combine with top-level AND
 * logic.
 * <p>
 * Examples:
 * 
 * <pre>
 * // Must have token "影视飓风"
 * {"have_token": ["影视飓风"]}
 *
 * // Equivalent to:
 * {"AND": {"have_token": ["影视飓风"]}}
 *
 * // Must have prefix "影视" OR "娱乐", must NOT have "影视飓风"
 * {"constraints": [
 *     {"with_prefixes": ["影视", "娱乐"]},
 *     {"NOT": {"have_token": ["影视飓风"]}}
 * ]}
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
     * Create a constraint with a single condition.
     */
    public SearchConstraint(BoolType boolType, MatchCondition condition) {
        this.boolType = boolType != null ? boolType : BoolType.AND;
        this.conditions = Collections.singletonList(
                condition != null ? condition : MatchCondition.EMPTY);
    }

    /**
     * Create a constraint with multiple conditions (for OR).
     */
    public SearchConstraint(BoolType boolType, List<MatchCondition> conditions) {
        this.boolType = boolType != null ? boolType : BoolType.AND;
        this.conditions = conditions != null ? new ArrayList<>(conditions) : Collections.emptyList();
    }

    public BoolType getBoolType() {
        return boolType;
    }

    public List<MatchCondition> getConditions() {
        return Collections.unmodifiableList(conditions);
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
        return boolType == that.boolType && Objects.equals(conditions, that.conditions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(boolType, conditions);
    }

    @Override
    public String toString() {
        return String.format("SearchConstraint{%s: %s}", boolType, conditions);
    }
}
