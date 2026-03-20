package org.es.tok.query;

import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.es.tok.suggest.LuceneIndexSuggester;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Query builder for a minimal analyzer-driven query syntax.
 * <p>
 * Supported syntax intentionally excludes Lucene query-string operators and
 * only keeps natural-language segments plus {@code +token}, {@code -token},
 * and quoted exact segments.
 */
public class EsTokQueryStringQueryBuilder extends AbstractQueryBuilder<EsTokQueryStringQueryBuilder> {

    public static final String NAME = "es_tok_query_string";

    public static final ParseField QUERY_FIELD = new ParseField("query");
    public static final ParseField FIELDS_FIELD = new ParseField("fields");
    public static final ParseField DEFAULT_FIELD_FIELD = new ParseField("default_field");
    public static final ParseField DEFAULT_OPERATOR_FIELD = new ParseField("default_operator");
    public static final ParseField ANALYZER_FIELD = new ParseField("analyzer");
    public static final ParseField QUOTE_ANALYZER_FIELD = new ParseField("quote_analyzer");
    public static final ParseField PHRASE_SLOP_FIELD = new ParseField("phrase_slop");
    public static final ParseField TIE_BREAKER_FIELD = new ParseField("tie_breaker");
    public static final ParseField MINIMUM_SHOULD_MATCH_FIELD = new ParseField("minimum_should_match");

    public static final ParseField CONSTRAINTS_FIELD = new ParseField("constraints");
    public static final ParseField MAX_FREQ_FIELD = new ParseField("max_freq");
    public static final ParseField SPELL_CORRECT_FIELD = new ParseField("spell_correct");
    public static final ParseField SPELL_CORRECT_RARE_DOC_FREQ_FIELD = new ParseField("spell_correct_rare_doc_freq");
    public static final ParseField SPELL_CORRECT_MIN_LENGTH_FIELD = new ParseField("spell_correct_min_length");
    public static final ParseField SPELL_CORRECT_MAX_EDITS_FIELD = new ParseField("spell_correct_max_edits");
    public static final ParseField SPELL_CORRECT_PREFIX_LENGTH_FIELD = new ParseField("spell_correct_prefix_length");
    public static final ParseField SPELL_CORRECT_SIZE_FIELD = new ParseField("spell_correct_size");

    private final String queryString;
    private final Map<String, Float> fieldsAndWeights = new LinkedHashMap<>();

    private String defaultField;
    private Operator defaultOperator = Operator.OR;
    private String analyzer;
    private String quoteAnalyzer;
    private int phraseSlop = 0;
    private Float tieBreaker;
    private String minimumShouldMatch;

    private List<SearchConstraint> constraints = Collections.emptyList();
    private int maxFreq = 0;
    private boolean spellCorrect = false;
    private int spellCorrectRareDocFreq = 0;
    private int spellCorrectMinLength = 4;
    private int spellCorrectMaxEdits = 2;
    private int spellCorrectPrefixLength = 1;
    private int spellCorrectSize = 3;

    public EsTokQueryStringQueryBuilder(String queryString) {
        if (queryString == null) {
            throw new IllegalArgumentException("[query] cannot be null");
        }
        this.queryString = queryString;
    }

    public EsTokQueryStringQueryBuilder(StreamInput in) throws IOException {
        super(in);
        this.queryString = in.readString();

        int fieldCount = in.readVInt();
        for (int index = 0; index < fieldCount; index++) {
            fieldsAndWeights.put(in.readString(), in.readFloat());
        }

        this.defaultField = in.readOptionalString();
        this.defaultOperator = Operator.readFromStream(in);
        this.analyzer = in.readOptionalString();
        this.quoteAnalyzer = in.readOptionalString();
        this.phraseSlop = in.readVInt();
        this.tieBreaker = in.readOptionalFloat();
        this.minimumShouldMatch = in.readOptionalString();

        int constraintCount = in.readVInt();
        if (constraintCount > 0) {
            List<SearchConstraint> readConstraints = new ArrayList<>(constraintCount);
            for (int index = 0; index < constraintCount; index++) {
                SearchConstraint.BoolType boolType = SearchConstraint.BoolType.values()[in.readVInt()];
                List<String> constraintFields = in.readStringCollectionAsList();

                int conditionCount = in.readVInt();
                List<MatchCondition> conditions = new ArrayList<>(conditionCount);
                for (int conditionIndex = 0; conditionIndex < conditionCount; conditionIndex++) {
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

        this.maxFreq = in.readVInt();
        this.spellCorrect = in.readBoolean();
        this.spellCorrectRareDocFreq = in.readVInt();
        this.spellCorrectMinLength = in.readVInt();
        this.spellCorrectMaxEdits = in.readVInt();
        this.spellCorrectPrefixLength = in.readVInt();
        this.spellCorrectSize = in.readVInt();
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(queryString);

        out.writeVInt(fieldsAndWeights.size());
        for (Map.Entry<String, Float> entry : fieldsAndWeights.entrySet()) {
            out.writeString(entry.getKey());
            out.writeFloat(entry.getValue());
        }

        out.writeOptionalString(defaultField);
        defaultOperator.writeTo(out);
        out.writeOptionalString(analyzer);
        out.writeOptionalString(quoteAnalyzer);
        out.writeVInt(phraseSlop);
        out.writeOptionalFloat(tieBreaker);
        out.writeOptionalString(minimumShouldMatch);

        out.writeVInt(constraints.size());
        for (SearchConstraint constraint : constraints) {
            out.writeVInt(constraint.getBoolType().ordinal());
            out.writeStringCollection(constraint.getFields());

            List<MatchCondition> conditionList = constraint.getConditions();
            out.writeVInt(conditionList.size());
            for (MatchCondition condition : conditionList) {
                out.writeStringCollection(condition.getHaveToken());
                out.writeStringCollection(condition.getWithPrefixes());
                out.writeStringCollection(condition.getWithSuffixes());
                out.writeStringCollection(condition.getWithContains());
                out.writeStringCollection(condition.getWithPatterns());
            }
        }

        out.writeVInt(maxFreq);
        out.writeBoolean(spellCorrect);
        out.writeVInt(spellCorrectRareDocFreq);
        out.writeVInt(spellCorrectMinLength);
        out.writeVInt(spellCorrectMaxEdits);
        out.writeVInt(spellCorrectPrefixLength);
        out.writeVInt(spellCorrectSize);
    }

    public String queryString() {
        return queryString;
    }

    public EsTokQueryStringQueryBuilder field(String field) {
        return field(field, 1.0f);
    }

    public EsTokQueryStringQueryBuilder field(String field, float boost) {
        if (field == null) {
            throw new IllegalArgumentException("[field] cannot be null");
        }
        fieldsAndWeights.put(field, boost);
        return this;
    }

    public EsTokQueryStringQueryBuilder fields(Map<String, Float> fields) {
        if (fields == null) {
            throw new IllegalArgumentException("[fields] cannot be null");
        }
        fieldsAndWeights.putAll(fields);
        return this;
    }

    public Map<String, Float> fields() {
        return Collections.unmodifiableMap(fieldsAndWeights);
    }

    public EsTokQueryStringQueryBuilder defaultField(String defaultField) {
        this.defaultField = defaultField;
        return this;
    }

    public String defaultField() {
        return defaultField;
    }

    public EsTokQueryStringQueryBuilder defaultOperator(Operator defaultOperator) {
        this.defaultOperator = defaultOperator != null ? defaultOperator : Operator.OR;
        return this;
    }

    public Operator defaultOperator() {
        return defaultOperator;
    }

    public EsTokQueryStringQueryBuilder analyzer(String analyzer) {
        this.analyzer = analyzer;
        return this;
    }

    public String analyzer() {
        return analyzer;
    }

    public EsTokQueryStringQueryBuilder quoteAnalyzer(String quoteAnalyzer) {
        this.quoteAnalyzer = quoteAnalyzer;
        return this;
    }

    public String quoteAnalyzer() {
        return quoteAnalyzer;
    }

    public EsTokQueryStringQueryBuilder phraseSlop(int phraseSlop) {
        if (phraseSlop < 0) {
            throw new IllegalArgumentException("[phrase_slop] cannot be negative");
        }
        this.phraseSlop = phraseSlop;
        return this;
    }

    public int phraseSlop() {
        return phraseSlop;
    }

    public EsTokQueryStringQueryBuilder tieBreaker(float tieBreaker) {
        this.tieBreaker = tieBreaker;
        return this;
    }

    public Float tieBreaker() {
        return tieBreaker;
    }

    public EsTokQueryStringQueryBuilder minimumShouldMatch(String minimumShouldMatch) {
        this.minimumShouldMatch = minimumShouldMatch;
        return this;
    }

    public String minimumShouldMatch() {
        return minimumShouldMatch;
    }

    public EsTokQueryStringQueryBuilder constraints(List<SearchConstraint> constraints) {
        this.constraints = constraints != null ? constraints : Collections.emptyList();
        return this;
    }

    public List<SearchConstraint> constraints() {
        return constraints;
    }

    public EsTokQueryStringQueryBuilder maxFreq(int maxFreq) {
        if (maxFreq < 0) {
            throw new IllegalArgumentException("[max_freq] cannot be negative");
        }
        this.maxFreq = maxFreq;
        return this;
    }

    public int maxFreq() {
        return maxFreq;
    }

    public EsTokQueryStringQueryBuilder spellCorrect(boolean spellCorrect) {
        this.spellCorrect = spellCorrect;
        return this;
    }

    public boolean spellCorrect() {
        return spellCorrect;
    }

    public EsTokQueryStringQueryBuilder spellCorrectRareDocFreq(int spellCorrectRareDocFreq) {
        if (spellCorrectRareDocFreq < 0) {
            throw new IllegalArgumentException("[spell_correct_rare_doc_freq] cannot be negative");
        }
        this.spellCorrectRareDocFreq = spellCorrectRareDocFreq;
        return this;
    }

    public int spellCorrectRareDocFreq() {
        return spellCorrectRareDocFreq;
    }

    public EsTokQueryStringQueryBuilder spellCorrectMinLength(int spellCorrectMinLength) {
        if (spellCorrectMinLength < 1) {
            throw new IllegalArgumentException("[spell_correct_min_length] must be positive");
        }
        this.spellCorrectMinLength = spellCorrectMinLength;
        return this;
    }

    public int spellCorrectMinLength() {
        return spellCorrectMinLength;
    }

    public EsTokQueryStringQueryBuilder spellCorrectMaxEdits(int spellCorrectMaxEdits) {
        if (spellCorrectMaxEdits < 1 || spellCorrectMaxEdits > 2) {
            throw new IllegalArgumentException("[spell_correct_max_edits] must be 1 or 2");
        }
        this.spellCorrectMaxEdits = spellCorrectMaxEdits;
        return this;
    }

    public int spellCorrectMaxEdits() {
        return spellCorrectMaxEdits;
    }

    public EsTokQueryStringQueryBuilder spellCorrectPrefixLength(int spellCorrectPrefixLength) {
        if (spellCorrectPrefixLength < 0) {
            throw new IllegalArgumentException("[spell_correct_prefix_length] cannot be negative");
        }
        this.spellCorrectPrefixLength = spellCorrectPrefixLength;
        return this;
    }

    public int spellCorrectPrefixLength() {
        return spellCorrectPrefixLength;
    }

    public EsTokQueryStringQueryBuilder spellCorrectSize(int spellCorrectSize) {
        if (spellCorrectSize < 1) {
            throw new IllegalArgumentException("[spell_correct_size] must be positive");
        }
        this.spellCorrectSize = spellCorrectSize;
        return this;
    }

    public int spellCorrectSize() {
        return spellCorrectSize;
    }

    @Override
    protected Query doToQuery(SearchExecutionContext context) throws IOException {
        EsTokQueryStringQueryParser parser = new EsTokQueryStringQueryParser(
                context,
                defaultField,
                fieldsAndWeights);

        parser.setDefaultOperator(defaultOperator);
        parser.setPhraseSlop(phraseSlop);
        parser.setTieBreaker(tieBreaker);
        parser.setMaxFreq(maxFreq);
        parser.setSuggestionFields(resolveQueryFields());

        if (analyzer != null) {
            parser.setForceAnalyzer(context.getIndexAnalyzers().get(analyzer));
        }
        if (quoteAnalyzer != null) {
            parser.setForceQuoteAnalyzer(context.getIndexAnalyzers().get(quoteAnalyzer));
        }
        if (spellCorrect) {
            parser.setCorrectionConfig(new LuceneIndexSuggester.CorrectionConfig(
                    spellCorrectRareDocFreq,
                    spellCorrectMinLength,
                    spellCorrectMaxEdits,
                    spellCorrectPrefixLength,
                    spellCorrectSize,
                    1,
                    0.5f));
        }

        Query query = parser.parse(queryString);
        if (minimumShouldMatch != null && query instanceof BooleanQuery booleanQuery) {
            query = org.elasticsearch.common.lucene.search.Queries.applyMinimumShouldMatch(
                    booleanQuery,
                    minimumShouldMatch);
        }
        if (constraints != null && constraints.isEmpty() == false) {
            query = ConstraintBuilder.buildConstrainedQuery(query, constraints, resolveQueryFields());
        }
        return query;
    }

    private List<String> resolveQueryFields() {
        if (fieldsAndWeights.isEmpty() == false) {
            return new ArrayList<>(fieldsAndWeights.keySet());
        }
        if (defaultField != null && defaultField.isBlank() == false) {
            return List.of(defaultField);
        }
        return List.of("*");
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.field(QUERY_FIELD.getPreferredName(), queryString);

        if (fieldsAndWeights.isEmpty() == false) {
            builder.startArray(FIELDS_FIELD.getPreferredName());
            for (Map.Entry<String, Float> entry : fieldsAndWeights.entrySet()) {
                if (entry.getValue() == 1.0f) {
                    builder.value(entry.getKey());
                } else {
                    builder.value(entry.getKey() + "^" + entry.getValue());
                }
            }
            builder.endArray();
        }
        if (defaultField != null) {
            builder.field(DEFAULT_FIELD_FIELD.getPreferredName(), defaultField);
        }
        builder.field(DEFAULT_OPERATOR_FIELD.getPreferredName(), defaultOperator.name().toLowerCase());
        if (analyzer != null) {
            builder.field(ANALYZER_FIELD.getPreferredName(), analyzer);
        }
        if (quoteAnalyzer != null) {
            builder.field(QUOTE_ANALYZER_FIELD.getPreferredName(), quoteAnalyzer);
        }
        if (phraseSlop != 0) {
            builder.field(PHRASE_SLOP_FIELD.getPreferredName(), phraseSlop);
        }
        if (tieBreaker != null) {
            builder.field(TIE_BREAKER_FIELD.getPreferredName(), tieBreaker);
        }
        if (minimumShouldMatch != null) {
            builder.field(MINIMUM_SHOULD_MATCH_FIELD.getPreferredName(), minimumShouldMatch);
        }

        if (constraints != null && constraints.isEmpty() == false) {
            builder.field(CONSTRAINTS_FIELD.getPreferredName(), ConstraintBuilder.constraintsToMaps(constraints));
        }
        if (maxFreq > 0) {
            builder.field(MAX_FREQ_FIELD.getPreferredName(), maxFreq);
        }
        if (spellCorrect) {
            builder.field(SPELL_CORRECT_FIELD.getPreferredName(), true);
        }
        if (spellCorrectRareDocFreq != 0) {
            builder.field(SPELL_CORRECT_RARE_DOC_FREQ_FIELD.getPreferredName(), spellCorrectRareDocFreq);
        }
        if (spellCorrectMinLength != 4) {
            builder.field(SPELL_CORRECT_MIN_LENGTH_FIELD.getPreferredName(), spellCorrectMinLength);
        }
        if (spellCorrectMaxEdits != 2) {
            builder.field(SPELL_CORRECT_MAX_EDITS_FIELD.getPreferredName(), spellCorrectMaxEdits);
        }
        if (spellCorrectPrefixLength != 1) {
            builder.field(SPELL_CORRECT_PREFIX_LENGTH_FIELD.getPreferredName(), spellCorrectPrefixLength);
        }
        if (spellCorrectSize != 3) {
            builder.field(SPELL_CORRECT_SIZE_FIELD.getPreferredName(), spellCorrectSize);
        }

        if (boost() != DEFAULT_BOOST) {
            builder.field(BOOST_FIELD.getPreferredName(), boost());
        }
        if (queryName() != null) {
            builder.field(NAME_FIELD.getPreferredName(), queryName());
        }
    }

    public static EsTokQueryStringQueryBuilder fromXContent(XContentParser parser) throws IOException {
        String queryString = null;
        Map<String, Float> fieldsAndWeights = new LinkedHashMap<>();
        String defaultField = null;
        Operator defaultOperator = Operator.OR;
        String analyzer = null;
        String quoteAnalyzer = null;
        int phraseSlop = 0;
        Float tieBreaker = null;
        String minimumShouldMatch = null;

        List<SearchConstraint> constraints = Collections.emptyList();
        int maxFreq = 0;
        boolean spellCorrect = false;
        int spellCorrectRareDocFreq = 0;
        int spellCorrectMinLength = 4;
        int spellCorrectMaxEdits = 2;
        int spellCorrectPrefixLength = 1;
        int spellCorrectSize = 3;
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
                        String fieldName = parser.text();
                        float weight = 1.0f;
                        int boostIndex = fieldName.indexOf('^');
                        if (boostIndex != -1) {
                            weight = Float.parseFloat(fieldName.substring(boostIndex + 1));
                            fieldName = fieldName.substring(0, boostIndex);
                        }
                        fieldsAndWeights.put(fieldName, weight);
                    }
                } else if (CONSTRAINTS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    constraints = ConstraintBuilder.parseConstraints(parser);
                } else {
                    throw new ParsingException(parser.getTokenLocation(),
                            "[" + NAME + "] query does not support array [" + currentFieldName + "]");
                }
            } else if (token.isValue()) {
                if (QUERY_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    queryString = parser.text();
                } else if (DEFAULT_FIELD_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    defaultField = parser.text();
                } else if (DEFAULT_OPERATOR_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    defaultOperator = Operator.fromString(parser.text());
                } else if (ANALYZER_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    analyzer = parser.text();
                } else if (QUOTE_ANALYZER_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    quoteAnalyzer = parser.text();
                } else if (PHRASE_SLOP_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    phraseSlop = parser.intValue();
                } else if (TIE_BREAKER_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    tieBreaker = parser.floatValue();
                } else if (MINIMUM_SHOULD_MATCH_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    minimumShouldMatch = parser.text();
                } else if (MAX_FREQ_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    maxFreq = parser.intValue();
                } else if (SPELL_CORRECT_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    spellCorrect = parser.booleanValue();
                } else if (SPELL_CORRECT_RARE_DOC_FREQ_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    spellCorrectRareDocFreq = parser.intValue();
                } else if (SPELL_CORRECT_MIN_LENGTH_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    spellCorrectMinLength = parser.intValue();
                } else if (SPELL_CORRECT_MAX_EDITS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    spellCorrectMaxEdits = parser.intValue();
                } else if (SPELL_CORRECT_PREFIX_LENGTH_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    spellCorrectPrefixLength = parser.intValue();
                } else if (SPELL_CORRECT_SIZE_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    spellCorrectSize = parser.intValue();
                } else if (AbstractQueryBuilder.BOOST_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    boost = parser.floatValue();
                } else if (AbstractQueryBuilder.NAME_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    queryName = parser.text();
                } else {
                    throw new ParsingException(parser.getTokenLocation(),
                            "[" + NAME + "] query does not support [" + currentFieldName + "]");
                }
            } else {
                throw new ParsingException(parser.getTokenLocation(),
                        "[" + NAME + "] unexpected token [" + token + "] for [" + currentFieldName + "]");
            }
        }

        if (queryString == null) {
            throw new ParsingException(parser.getTokenLocation(), "[" + NAME + "] requires 'query' field");
        }

        EsTokQueryStringQueryBuilder queryBuilder = new EsTokQueryStringQueryBuilder(queryString);
        queryBuilder.fields(fieldsAndWeights);
        queryBuilder.defaultField(defaultField);
        queryBuilder.defaultOperator(defaultOperator);
        queryBuilder.analyzer(analyzer);
        queryBuilder.quoteAnalyzer(quoteAnalyzer);
        queryBuilder.phraseSlop(phraseSlop);
        if (tieBreaker != null) {
            queryBuilder.tieBreaker(tieBreaker);
        }
        if (minimumShouldMatch != null) {
            queryBuilder.minimumShouldMatch(minimumShouldMatch);
        }
        queryBuilder.constraints(constraints);
        queryBuilder.maxFreq(maxFreq);
        queryBuilder.spellCorrect(spellCorrect);
        queryBuilder.spellCorrectRareDocFreq(spellCorrectRareDocFreq);
        queryBuilder.spellCorrectMinLength(spellCorrectMinLength);
        queryBuilder.spellCorrectMaxEdits(spellCorrectMaxEdits);
        queryBuilder.spellCorrectPrefixLength(spellCorrectPrefixLength);
        queryBuilder.spellCorrectSize(spellCorrectSize);
        queryBuilder.boost(boost);
        queryBuilder.queryName(queryName);
        return queryBuilder;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return TransportVersion.zero();
    }

    @Override
    protected boolean doEquals(EsTokQueryStringQueryBuilder other) {
        return Objects.equals(queryString, other.queryString)
                && Objects.equals(fieldsAndWeights, other.fieldsAndWeights)
                && Objects.equals(defaultField, other.defaultField)
                && defaultOperator == other.defaultOperator
                && Objects.equals(analyzer, other.analyzer)
                && Objects.equals(quoteAnalyzer, other.quoteAnalyzer)
                && phraseSlop == other.phraseSlop
                && Objects.equals(tieBreaker, other.tieBreaker)
                && Objects.equals(minimumShouldMatch, other.minimumShouldMatch)
                && Objects.equals(constraints, other.constraints)
                && maxFreq == other.maxFreq
                && spellCorrect == other.spellCorrect
                && spellCorrectRareDocFreq == other.spellCorrectRareDocFreq
                && spellCorrectMinLength == other.spellCorrectMinLength
                && spellCorrectMaxEdits == other.spellCorrectMaxEdits
                && spellCorrectPrefixLength == other.spellCorrectPrefixLength
                && spellCorrectSize == other.spellCorrectSize;
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(
                queryString,
                fieldsAndWeights,
                defaultField,
                defaultOperator,
                analyzer,
                quoteAnalyzer,
                phraseSlop,
                tieBreaker,
                minimumShouldMatch,
                constraints,
                maxFreq,
                spellCorrect,
                spellCorrectRareDocFreq,
                spellCorrectMinLength,
                spellCorrectMaxEdits,
                spellCorrectPrefixLength,
                spellCorrectSize);
    }
}
