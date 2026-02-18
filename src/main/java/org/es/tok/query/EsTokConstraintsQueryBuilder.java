package org.es.tok.query;

import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Standalone constraint-based document filter query.
 * <p>
 * This query filters documents based on their indexed tokens without requiring
 * a text query. It's designed to be used as a KNN search filter, enabling
 * token-level constraints on vector search results.
 * <p>
 * Unlike {@code es_tok_query_string}, this query does not parse or score text.
 * It only applies constraint-based filtering using the same constraint syntax:
 * AND/OR/NOT with have_token, with_prefixes, with_suffixes, with_contains,
 * with_patterns.
 * <p>
 * Each constraint can optionally specify {@code fields} to target specific
 * index fields, overriding the top-level {@code fields} default.
 * <p>
 * Usage as KNN filter:
 * 
 * <pre>{@code
 * POST /index/_search
 * {
 *   "knn": {
 *     "field": "text_emb",
 *     "query_vector": [...],
 *     "k": 100,
 *     "num_candidates": 1000,
 *     "filter": {
 *       "es_tok_constraints": {
 *         "fields": ["title", "tags"],
 *         "constraints": [
 *           {"have_token": ["关键词"]},
 *           {"NOT": {"have_token": ["广告"]}, "fields": ["title"]}
 *         ]
 *       }
 *     }
 *   }
 * }
 * }</pre>
 * <p>
 * Can also be used standalone or combined with other queries via bool:
 * 
 * <pre>{@code
 * POST /index/_search
 * {
 *   "query": {
 *     "es_tok_constraints": {
 *       "constraints": [
 *         {"with_prefixes": ["深度"], "fields": ["title^3"]},
 *         {"NOT": {"with_contains": ["广告"]}, "fields": ["title", "tags"]}
 *       ]
 *     }
 *   }
 * }
 * }</pre>
 */
public class EsTokConstraintsQueryBuilder extends AbstractQueryBuilder<EsTokConstraintsQueryBuilder> {

    public static final String NAME = "es_tok_constraints";

    public static final ParseField FIELDS_FIELD = new ParseField("fields");
    public static final ParseField CONSTRAINTS_FIELD = new ParseField("constraints");

    /** Default fields for constraints that don't specify their own. */
    private final List<String> fields;
    private final List<SearchConstraint> constraints;

    // ===== Constructors =====

    /**
     * Create a constraint query with default fields and constraints.
     *
     * @param fields      default fields for constraints without per-constraint
     *                    fields (e.g., ["title", "tags"]). Supports boost syntax
     *                    (e.g., "title^3"). If null/empty, defaults to ["*"].
     * @param constraints the constraint conditions to apply. Each constraint
     *                    can optionally specify its own fields.
     */
    public EsTokConstraintsQueryBuilder(List<String> fields, List<SearchConstraint> constraints) {
        if (constraints == null || constraints.isEmpty()) {
            throw new IllegalArgumentException("[constraints] cannot be null or empty");
        }
        this.fields = fields != null && !fields.isEmpty() ? new ArrayList<>(fields) : List.of("*");
        this.constraints = new ArrayList<>(constraints);
    }

    /**
     * Deserialization constructor.
     */
    public EsTokConstraintsQueryBuilder(StreamInput in) throws IOException {
        super(in);
        this.fields = in.readStringCollectionAsList();

        int constraintCount = in.readVInt();
        List<SearchConstraint> readConstraints = new ArrayList<>(constraintCount);
        for (int i = 0; i < constraintCount; i++) {
            SearchConstraint.BoolType boolType = SearchConstraint.BoolType.values()[in.readVInt()];

            // Read per-constraint fields
            List<String> constraintFields = in.readStringCollectionAsList();

            int condCount = in.readVInt();
            List<MatchCondition> conditions = new ArrayList<>(condCount);
            for (int j = 0; j < condCount; j++) {
                conditions.add(new MatchCondition(
                        in.readStringCollectionAsList(),
                        in.readStringCollectionAsList(),
                        in.readStringCollectionAsList(),
                        in.readStringCollectionAsList(),
                        in.readStringCollectionAsList()));
            }
            readConstraints.add(new SearchConstraint(boolType, conditions,
                    constraintFields.isEmpty() ? null : constraintFields));
        }
        this.constraints = readConstraints;
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeStringCollection(fields);

        out.writeVInt(constraints.size());
        for (SearchConstraint constraint : constraints) {
            out.writeVInt(constraint.getBoolType().ordinal());

            // Write per-constraint fields
            out.writeStringCollection(constraint.getFields());

            List<MatchCondition> conds = constraint.getConditions();
            out.writeVInt(conds.size());
            for (MatchCondition cond : conds) {
                out.writeStringCollection(cond.getHaveToken());
                out.writeStringCollection(cond.getWithPrefixes());
                out.writeStringCollection(cond.getWithSuffixes());
                out.writeStringCollection(cond.getWithContains());
                out.writeStringCollection(cond.getWithPatterns());
            }
        }
    }

    // ===== Getters =====

    public List<String> fields() {
        return Collections.unmodifiableList(fields);
    }

    public List<SearchConstraint> constraints() {
        return Collections.unmodifiableList(constraints);
    }

    // ===== Query building =====

    @Override
    protected Query doToQuery(SearchExecutionContext context) throws IOException {
        // Filter out empty constraints
        List<SearchConstraint> activeConstraints = constraints.stream()
                .filter(c -> !c.isEmpty())
                .toList();

        if (activeConstraints.isEmpty()) {
            return new MatchAllDocsQuery();
        }

        // Build constraint query using ConstraintBuilder
        // Use MatchAllDocsQuery as base since we only want constraint filtering
        Query baseQuery = new MatchAllDocsQuery();

        // Default fields for constraints without per-constraint fields
        List<String> defaultFields = new ArrayList<>(fields);
        if (defaultFields.isEmpty()) {
            defaultFields.add("*");
        }

        return ConstraintBuilder.buildConstrainedQuery(baseQuery, activeConstraints, defaultFields);
    }

    // ===== XContent serialization =====

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        if (!fields.isEmpty()) {
            builder.startArray(FIELDS_FIELD.getPreferredName());
            for (String field : fields) {
                builder.value(field);
            }
            builder.endArray();
        }

        if (!constraints.isEmpty()) {
            builder.field(CONSTRAINTS_FIELD.getPreferredName(),
                    ConstraintBuilder.constraintsToMaps(constraints));
        }

        if (boost() != DEFAULT_BOOST) {
            builder.field(BOOST_FIELD.getPreferredName(), boost());
        }
        if (queryName() != null) {
            builder.field(NAME_FIELD.getPreferredName(), queryName());
        }
    }

    // ===== XContent parsing =====

    public static EsTokConstraintsQueryBuilder fromXContent(XContentParser parser) throws IOException {
        List<String> fields = new ArrayList<>();
        List<SearchConstraint> constraints = Collections.emptyList();
        float boost = DEFAULT_BOOST;
        String queryName = null;

        String currentFieldName = null;
        XContentParser.Token token;

        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_ARRAY) {
                if (FIELDS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                        if (token.isValue()) {
                            fields.add(parser.text());
                        }
                    }
                } else if (CONSTRAINTS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    constraints = ConstraintBuilder.parseConstraints(parser);
                } else {
                    throw new ParsingException(parser.getTokenLocation(),
                            "[" + NAME + "] does not support array [" + currentFieldName + "]");
                }
            } else if (token.isValue()) {
                if (AbstractQueryBuilder.BOOST_FIELD.match(currentFieldName,
                        parser.getDeprecationHandler())) {
                    boost = parser.floatValue();
                } else if (AbstractQueryBuilder.NAME_FIELD.match(currentFieldName,
                        parser.getDeprecationHandler())) {
                    queryName = parser.text();
                } else {
                    throw new ParsingException(parser.getTokenLocation(),
                            "[" + NAME + "] does not support [" + currentFieldName + "]");
                }
            } else {
                throw new ParsingException(parser.getTokenLocation(),
                        "[" + NAME + "] unexpected token [" + token + "]");
            }
        }

        if (constraints.isEmpty()) {
            throw new ParsingException(parser.getTokenLocation(),
                    "[" + NAME + "] requires 'constraints' field");
        }

        EsTokConstraintsQueryBuilder queryBuilder = new EsTokConstraintsQueryBuilder(fields, constraints);
        queryBuilder.boost(boost);
        queryBuilder.queryName(queryName);
        return queryBuilder;
    }

    // ===== Standard overrides =====

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return TransportVersion.zero();
    }

    @Override
    protected boolean doEquals(EsTokConstraintsQueryBuilder other) {
        return Objects.equals(fields, other.fields)
                && Objects.equals(constraints, other.constraints);
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(fields, constraints);
    }
}
