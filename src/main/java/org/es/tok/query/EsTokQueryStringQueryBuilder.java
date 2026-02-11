package org.es.tok.query;

import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.TransportVersion;

import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.es.tok.rules.RulesLoader;
import org.es.tok.rules.SearchRules;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Extended QueryString query with token filtering support via SearchRules.
 * <p>
 * Supports a {@code rules} object containing:
 * <ul>
 * <li>{@code exclude_tokens} - exact token match exclusion</li>
 * <li>{@code exclude_prefixes} - prefix match exclusion</li>
 * <li>{@code exclude_suffixes} - suffix match exclusion</li>
 * <li>{@code exclude_contains} - substring match exclusion</li>
 * <li>{@code exclude_patterns} - regex pattern match exclusion</li>
 * <li>{@code include_tokens} - exact token match inclusion (overrides
 * exclude)</li>
 * <li>{@code include_prefixes} - prefix match inclusion (overrides
 * exclude)</li>
 * <li>{@code include_suffixes} - suffix match inclusion (overrides
 * exclude)</li>
 * <li>{@code include_contains} - substring match inclusion (overrides
 * exclude)</li>
 * <li>{@code include_patterns} - regex pattern match inclusion (overrides
 * exclude)</li>
 * <li>{@code declude_prefixes} - context-dependent prefix exclusion (when base
 * form exists)</li>
 * <li>{@code declude_suffixes} - context-dependent suffix exclusion (when base
 * form exists)</li>
 * <li>{@code file} - load rules from a JSON file</li>
 * </ul>
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
    public static final ParseField FUZZINESS_FIELD = new ParseField("fuzziness");
    public static final ParseField FUZZY_PREFIX_LENGTH_FIELD = new ParseField("fuzzy_prefix_length");
    public static final ParseField FUZZY_MAX_EXPANSIONS_FIELD = new ParseField("fuzzy_max_expansions");
    public static final ParseField FUZZY_TRANSPOSITIONS_FIELD = new ParseField("fuzzy_transpositions");
    public static final ParseField FUZZY_REWRITE_FIELD = new ParseField("fuzzy_rewrite");
    public static final ParseField LENIENT_FIELD = new ParseField("lenient");
    public static final ParseField ANALYZE_WILDCARD_FIELD = new ParseField("analyze_wildcard");
    public static final ParseField QUOTE_FIELD_SUFFIX_FIELD = new ParseField("quote_field_suffix");
    public static final ParseField TIME_ZONE_FIELD = new ParseField("time_zone");
    public static final ParseField TYPE_FIELD = new ParseField("type");
    public static final ParseField TIE_BREAKER_FIELD = new ParseField("tie_breaker");
    public static final ParseField REWRITE_FIELD = new ParseField("rewrite");
    public static final ParseField MINIMUM_SHOULD_MATCH_FIELD = new ParseField("minimum_should_match");
    public static final ParseField ENABLE_POSITION_INCREMENTS_FIELD = new ParseField("enable_position_increments");
    public static final ParseField MAX_DETERMINIZED_STATES_FIELD = new ParseField("max_determinized_states");
    public static final ParseField AUTO_GENERATE_SYNONYMS_PHRASE_QUERY_FIELD = new ParseField(
            "auto_generate_synonyms_phrase_query");

    public static final ParseField RULES_FIELD = new ParseField("rules");
    public static final ParseField MAX_FREQ_FIELD = new ParseField("max_freq");
    public static final ParseField MIN_KEPT_TOKENS_COUNT_FIELD = new ParseField("min_kept_tokens_count");
    public static final ParseField MIN_KEPT_TOKENS_RATIO_FIELD = new ParseField("min_kept_tokens_ratio");

    // Rules sub-fields
    public static final ParseField EXCLUDE_TOKENS_FIELD = new ParseField("exclude_tokens");
    public static final ParseField EXCLUDE_PREFIXES_FIELD = new ParseField("exclude_prefixes");
    public static final ParseField EXCLUDE_SUFFIXES_FIELD = new ParseField("exclude_suffixes");
    public static final ParseField EXCLUDE_CONTAINS_FIELD = new ParseField("exclude_contains");
    public static final ParseField EXCLUDE_PATTERNS_FIELD = new ParseField("exclude_patterns");
    public static final ParseField INCLUDE_TOKENS_FIELD = new ParseField("include_tokens");
    public static final ParseField INCLUDE_PREFIXES_FIELD = new ParseField("include_prefixes");
    public static final ParseField INCLUDE_SUFFIXES_FIELD = new ParseField("include_suffixes");
    public static final ParseField INCLUDE_CONTAINS_FIELD = new ParseField("include_contains");
    public static final ParseField INCLUDE_PATTERNS_FIELD = new ParseField("include_patterns");
    public static final ParseField DECLUDE_PREFIXES_FIELD = new ParseField("declude_prefixes");
    public static final ParseField DECLUDE_SUFFIXES_FIELD = new ParseField("declude_suffixes");
    public static final ParseField FILE_FIELD = new ParseField("file");

    private final String queryString;
    private final Map<String, Float> fieldsAndWeights = new HashMap<>();
    private String defaultField;
    private Operator defaultOperator = Operator.OR;
    private String analyzer;
    private String quoteAnalyzer;
    private String quoteFieldSuffix;
    private int phraseSlop = 0;
    private Fuzziness fuzziness = Fuzziness.AUTO;
    private int fuzzyPrefixLength = 1;
    private int fuzzyMaxExpansions = 50;
    private boolean fuzzyTranspositions = true;
    private String fuzzyRewrite;
    private Boolean lenient;
    private Boolean analyzeWildcard;
    private ZoneId timeZone;
    private MultiMatchQueryBuilder.Type type = MultiMatchQueryBuilder.Type.BEST_FIELDS;
    private Float tieBreaker;
    private String rewrite;
    private String minimumShouldMatch;
    private boolean enablePositionIncrements = true;
    private int maxDeterminizedStates = 10000;
    private boolean autoGenerateSynonymsPhraseQuery = true;

    private SearchRules searchRules = SearchRules.EMPTY;
    private int maxFreq = 0;
    private int minKeptTokensCount = 1;
    private float minKeptTokensRatio = -1.0f;

    public EsTokQueryStringQueryBuilder(String queryString) {
        if (queryString == null) {
            throw new IllegalArgumentException("[query] cannot be null");
        }
        this.queryString = queryString;
    }

    public EsTokQueryStringQueryBuilder(StreamInput in) throws IOException {
        super(in);
        this.queryString = in.readString();
        int size = in.readVInt();
        for (int i = 0; i < size; i++) {
            fieldsAndWeights.put(in.readString(), in.readFloat());
        }
        this.defaultField = in.readOptionalString();
        this.defaultOperator = Operator.readFromStream(in);
        this.analyzer = in.readOptionalString();
        this.quoteAnalyzer = in.readOptionalString();
        this.quoteFieldSuffix = in.readOptionalString();
        this.phraseSlop = in.readVInt();
        this.fuzziness = new Fuzziness(in);
        this.fuzzyPrefixLength = in.readVInt();
        this.fuzzyMaxExpansions = in.readVInt();
        this.fuzzyTranspositions = in.readBoolean();
        this.fuzzyRewrite = in.readOptionalString();
        this.lenient = in.readOptionalBoolean();
        this.analyzeWildcard = in.readOptionalBoolean();
        this.timeZone = in.readOptionalZoneId();
        this.type = MultiMatchQueryBuilder.Type.readFromStream(in);
        this.tieBreaker = in.readOptionalFloat();
        this.rewrite = in.readOptionalString();
        this.minimumShouldMatch = in.readOptionalString();
        this.enablePositionIncrements = in.readBoolean();
        this.maxDeterminizedStates = in.readVInt();
        this.autoGenerateSynonymsPhraseQuery = in.readBoolean();
        // Read SearchRules (12 lists: 5 exclude + 5 include + 2 declude)
        List<String> excludeTokens = in.readStringCollectionAsList();
        List<String> excludePrefixes = in.readStringCollectionAsList();
        List<String> excludeSuffixes = in.readStringCollectionAsList();
        List<String> excludeContains = in.readStringCollectionAsList();
        List<String> excludePatterns = in.readStringCollectionAsList();
        List<String> includeTokens = in.readStringCollectionAsList();
        List<String> includePrefixes = in.readStringCollectionAsList();
        List<String> includeSuffixes = in.readStringCollectionAsList();
        List<String> includeContains = in.readStringCollectionAsList();
        List<String> includePatterns = in.readStringCollectionAsList();
        List<String> decludePrefixes = in.readStringCollectionAsList();
        List<String> decludeSuffixes = in.readStringCollectionAsList();
        this.searchRules = new SearchRules(excludeTokens, excludePrefixes, excludeSuffixes,
                excludeContains, excludePatterns,
                includeTokens, includePrefixes, includeSuffixes,
                includeContains, includePatterns,
                decludePrefixes, decludeSuffixes);
        this.maxFreq = in.readVInt();
        this.minKeptTokensCount = in.readVInt();
        this.minKeptTokensRatio = in.readFloat();
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
        out.writeOptionalString(quoteFieldSuffix);
        out.writeVInt(phraseSlop);
        fuzziness.writeTo(out);
        out.writeVInt(fuzzyPrefixLength);
        out.writeVInt(fuzzyMaxExpansions);
        out.writeBoolean(fuzzyTranspositions);
        out.writeOptionalString(fuzzyRewrite);
        out.writeOptionalBoolean(lenient);
        out.writeOptionalBoolean(analyzeWildcard);
        out.writeOptionalZoneId(timeZone);
        type.writeTo(out);
        out.writeOptionalFloat(tieBreaker);
        out.writeOptionalString(rewrite);
        out.writeOptionalString(minimumShouldMatch);
        out.writeBoolean(enablePositionIncrements);
        out.writeVInt(maxDeterminizedStates);
        out.writeBoolean(autoGenerateSynonymsPhraseQuery);
        // Write SearchRules (12 lists: 5 exclude + 5 include + 2 declude)
        out.writeStringCollection(searchRules.getExcludeTokens());
        out.writeStringCollection(searchRules.getExcludePrefixes());
        out.writeStringCollection(searchRules.getExcludeSuffixes());
        out.writeStringCollection(searchRules.getExcludeContains());
        out.writeStringCollection(searchRules.getExcludePatterns());
        out.writeStringCollection(searchRules.getIncludeTokens());
        out.writeStringCollection(searchRules.getIncludePrefixes());
        out.writeStringCollection(searchRules.getIncludeSuffixes());
        out.writeStringCollection(searchRules.getIncludeContains());
        out.writeStringCollection(searchRules.getIncludePatterns());
        out.writeStringCollection(searchRules.getDecludePrefixes());
        out.writeStringCollection(searchRules.getDecludeSuffixes());
        out.writeVInt(maxFreq);
        out.writeVInt(minKeptTokensCount);
        out.writeFloat(minKeptTokensRatio);
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
        this.fieldsAndWeights.put(field, boost);
        return this;
    }

    public EsTokQueryStringQueryBuilder fields(Map<String, Float> fields) {
        if (fields == null) {
            throw new IllegalArgumentException("[fields] cannot be null");
        }
        this.fieldsAndWeights.putAll(fields);
        return this;
    }

    public Map<String, Float> fields() {
        return fieldsAndWeights;
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

    public EsTokQueryStringQueryBuilder quoteFieldSuffix(String quoteFieldSuffix) {
        this.quoteFieldSuffix = quoteFieldSuffix;
        return this;
    }

    public String quoteFieldSuffix() {
        return quoteFieldSuffix;
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

    public EsTokQueryStringQueryBuilder fuzziness(Fuzziness fuzziness) {
        this.fuzziness = fuzziness != null ? fuzziness : Fuzziness.AUTO;
        return this;
    }

    public Fuzziness fuzziness() {
        return fuzziness;
    }

    public EsTokQueryStringQueryBuilder fuzzyPrefixLength(int fuzzyPrefixLength) {
        if (fuzzyPrefixLength < 0) {
            throw new IllegalArgumentException("[fuzzy_prefix_length] cannot be negative");
        }
        this.fuzzyPrefixLength = fuzzyPrefixLength;
        return this;
    }

    public int fuzzyPrefixLength() {
        return fuzzyPrefixLength;
    }

    public EsTokQueryStringQueryBuilder fuzzyMaxExpansions(int fuzzyMaxExpansions) {
        if (fuzzyMaxExpansions <= 0) {
            throw new IllegalArgumentException("[fuzzy_max_expansions] must be positive");
        }
        this.fuzzyMaxExpansions = fuzzyMaxExpansions;
        return this;
    }

    public int fuzzyMaxExpansions() {
        return fuzzyMaxExpansions;
    }

    public EsTokQueryStringQueryBuilder fuzzyTranspositions(boolean fuzzyTranspositions) {
        this.fuzzyTranspositions = fuzzyTranspositions;
        return this;
    }

    public boolean fuzzyTranspositions() {
        return fuzzyTranspositions;
    }

    public EsTokQueryStringQueryBuilder fuzzyRewrite(String fuzzyRewrite) {
        this.fuzzyRewrite = fuzzyRewrite;
        return this;
    }

    public String fuzzyRewrite() {
        return fuzzyRewrite;
    }

    public EsTokQueryStringQueryBuilder lenient(boolean lenient) {
        this.lenient = lenient;
        return this;
    }

    public Boolean lenient() {
        return lenient;
    }

    public EsTokQueryStringQueryBuilder analyzeWildcard(boolean analyzeWildcard) {
        this.analyzeWildcard = analyzeWildcard;
        return this;
    }

    public Boolean analyzeWildcard() {
        return analyzeWildcard;
    }

    public EsTokQueryStringQueryBuilder timeZone(ZoneId timeZone) {
        this.timeZone = timeZone;
        return this;
    }

    public ZoneId timeZone() {
        return timeZone;
    }

    public EsTokQueryStringQueryBuilder type(MultiMatchQueryBuilder.Type type) {
        this.type = type != null ? type : MultiMatchQueryBuilder.Type.BEST_FIELDS;
        return this;
    }

    public MultiMatchQueryBuilder.Type type() {
        return type;
    }

    public EsTokQueryStringQueryBuilder tieBreaker(float tieBreaker) {
        this.tieBreaker = tieBreaker;
        return this;
    }

    public Float tieBreaker() {
        return tieBreaker;
    }

    public EsTokQueryStringQueryBuilder rewrite(String rewrite) {
        this.rewrite = rewrite;
        return this;
    }

    public String rewrite() {
        return rewrite;
    }

    public EsTokQueryStringQueryBuilder minimumShouldMatch(String minimumShouldMatch) {
        this.minimumShouldMatch = minimumShouldMatch;
        return this;
    }

    public String minimumShouldMatch() {
        return minimumShouldMatch;
    }

    public EsTokQueryStringQueryBuilder enablePositionIncrements(boolean enablePositionIncrements) {
        this.enablePositionIncrements = enablePositionIncrements;
        return this;
    }

    public boolean enablePositionIncrements() {
        return enablePositionIncrements;
    }

    public EsTokQueryStringQueryBuilder maxDeterminizedStates(int maxDeterminizedStates) {
        if (maxDeterminizedStates <= 0) {
            throw new IllegalArgumentException("[max_determinized_states] must be positive");
        }
        this.maxDeterminizedStates = maxDeterminizedStates;
        return this;
    }

    public int maxDeterminizedStates() {
        return maxDeterminizedStates;
    }

    public EsTokQueryStringQueryBuilder autoGenerateSynonymsPhraseQuery(boolean autoGenerateSynonymsPhraseQuery) {
        this.autoGenerateSynonymsPhraseQuery = autoGenerateSynonymsPhraseQuery;
        return this;
    }

    public boolean autoGenerateSynonymsPhraseQuery() {
        return autoGenerateSynonymsPhraseQuery;
    }

    /**
     * Set the search rules for token exclusion.
     */
    public EsTokQueryStringQueryBuilder searchRules(SearchRules searchRules) {
        if (searchRules == null) {
            throw new IllegalArgumentException("[rules] cannot be null");
        }
        this.searchRules = searchRules;
        return this;
    }

    /**
     * Get the search rules for token exclusion.
     */
    public SearchRules searchRules() {
        return searchRules;
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

    public EsTokQueryStringQueryBuilder minKeptTokensCount(int minKeptTokensCount) {
        this.minKeptTokensCount = minKeptTokensCount;
        return this;
    }

    public int minKeptTokensCount() {
        return minKeptTokensCount;
    }

    public EsTokQueryStringQueryBuilder minKeptTokensRatio(float minKeptTokensRatio) {
        this.minKeptTokensRatio = minKeptTokensRatio;
        return this;
    }

    public float minKeptTokensRatio() {
        return minKeptTokensRatio;
    }

    @Override
    protected Query doToQuery(SearchExecutionContext context) throws IOException {
        EsTokQueryStringQueryParser parser = new EsTokQueryStringQueryParser(
                context,
                fieldsAndWeights.isEmpty() ? (defaultField != null ? defaultField : "*") : null,
                fieldsAndWeights,
                lenient != null ? lenient : false);

        parser.setSearchRules(searchRules);
        parser.setMaxFreq(maxFreq);
        parser.setMinKeptTokensCount(minKeptTokensCount);
        parser.setMinKeptTokensRatio(minKeptTokensRatio);

        parser.setDefaultOperator(
                defaultOperator == Operator.AND ? org.apache.lucene.queryparser.classic.QueryParser.Operator.AND
                        : org.apache.lucene.queryparser.classic.QueryParser.Operator.OR);
        parser.setEnablePositionIncrements(enablePositionIncrements);
        parser.setPhraseSlop(phraseSlop);
        parser.setFuzziness(fuzziness);
        parser.setFuzzyMaxExpansions(fuzzyMaxExpansions);
        parser.setFuzzyPrefixLength(fuzzyPrefixLength);
        parser.setFuzzyTranspositions(fuzzyTranspositions);

        if (analyzer != null) {
            parser.setForceAnalyzer(context.getIndexAnalyzers().get(analyzer));
        }
        if (quoteAnalyzer != null) {
            parser.setForceQuoteAnalyzer(context.getIndexAnalyzers().get(quoteAnalyzer));
        }
        if (quoteFieldSuffix != null) {
            parser.setQuoteFieldSuffix(quoteFieldSuffix);
        }
        if (type != null) {
            parser.setType(type);
        }
        if (tieBreaker != null) {
            parser.setGroupTieBreaker(tieBreaker);
        }
        if (analyzeWildcard != null) {
            parser.setAnalyzeWildcard(analyzeWildcard);
        }
        if (timeZone != null) {
            parser.setTimeZone(timeZone);
        }

        parser.setAutoGenerateMultiTermSynonymsPhraseQuery(autoGenerateSynonymsPhraseQuery);

        try {
            Query query = parser.parse(queryString);
            if (minimumShouldMatch != null && query instanceof BooleanQuery) {
                query = org.elasticsearch.common.lucene.search.Queries.applyMinimumShouldMatch(
                        (BooleanQuery) query,
                        minimumShouldMatch);
            }
            return query;
        } catch (org.apache.lucene.queryparser.classic.ParseException e) {
            throw new IOException("Failed to parse query [" + queryString + "]", e);
        }
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.field(QUERY_FIELD.getPreferredName(), queryString);

        if (!fieldsAndWeights.isEmpty()) {
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
        if (quoteFieldSuffix != null) {
            builder.field(QUOTE_FIELD_SUFFIX_FIELD.getPreferredName(), quoteFieldSuffix);
        }
        if (phraseSlop != 0) {
            builder.field(PHRASE_SLOP_FIELD.getPreferredName(), phraseSlop);
        }
        if (!fuzziness.equals(Fuzziness.AUTO)) {
            fuzziness.toXContent(builder, params);
        }
        if (fuzzyPrefixLength != 1) {
            builder.field(FUZZY_PREFIX_LENGTH_FIELD.getPreferredName(), fuzzyPrefixLength);
        }
        if (fuzzyMaxExpansions != 50) {
            builder.field(FUZZY_MAX_EXPANSIONS_FIELD.getPreferredName(), fuzzyMaxExpansions);
        }
        if (!fuzzyTranspositions) {
            builder.field(FUZZY_TRANSPOSITIONS_FIELD.getPreferredName(), fuzzyTranspositions);
        }
        if (fuzzyRewrite != null) {
            builder.field(FUZZY_REWRITE_FIELD.getPreferredName(), fuzzyRewrite);
        }
        if (lenient != null) {
            builder.field(LENIENT_FIELD.getPreferredName(), lenient);
        }
        if (analyzeWildcard != null) {
            builder.field(ANALYZE_WILDCARD_FIELD.getPreferredName(), analyzeWildcard);
        }
        if (timeZone != null) {
            builder.field(TIME_ZONE_FIELD.getPreferredName(), timeZone.getId());
        }
        if (type != MultiMatchQueryBuilder.Type.BEST_FIELDS) {
            builder.field(TYPE_FIELD.getPreferredName(), type.toString().toLowerCase());
        }
        if (tieBreaker != null) {
            builder.field(TIE_BREAKER_FIELD.getPreferredName(), tieBreaker);
        }
        if (rewrite != null) {
            builder.field(REWRITE_FIELD.getPreferredName(), rewrite);
        }
        if (minimumShouldMatch != null) {
            builder.field(MINIMUM_SHOULD_MATCH_FIELD.getPreferredName(), minimumShouldMatch);
        }
        if (!enablePositionIncrements) {
            builder.field(ENABLE_POSITION_INCREMENTS_FIELD.getPreferredName(), enablePositionIncrements);
        }
        if (maxDeterminizedStates != 10000) {
            builder.field(MAX_DETERMINIZED_STATES_FIELD.getPreferredName(), maxDeterminizedStates);
        }
        if (!autoGenerateSynonymsPhraseQuery) {
            builder.field(AUTO_GENERATE_SYNONYMS_PHRASE_QUERY_FIELD.getPreferredName(),
                    autoGenerateSynonymsPhraseQuery);
        }

        // Serialize rules object
        if (!searchRules.isEmpty()) {
            builder.startObject(RULES_FIELD.getPreferredName());
            if (!searchRules.getExcludeTokens().isEmpty()) {
                builder.field(EXCLUDE_TOKENS_FIELD.getPreferredName(), searchRules.getExcludeTokens());
            }
            if (!searchRules.getExcludePrefixes().isEmpty()) {
                builder.field(EXCLUDE_PREFIXES_FIELD.getPreferredName(), searchRules.getExcludePrefixes());
            }
            if (!searchRules.getExcludeSuffixes().isEmpty()) {
                builder.field(EXCLUDE_SUFFIXES_FIELD.getPreferredName(), searchRules.getExcludeSuffixes());
            }
            if (!searchRules.getExcludeContains().isEmpty()) {
                builder.field(EXCLUDE_CONTAINS_FIELD.getPreferredName(), searchRules.getExcludeContains());
            }
            if (!searchRules.getExcludePatterns().isEmpty()) {
                builder.field(EXCLUDE_PATTERNS_FIELD.getPreferredName(), searchRules.getExcludePatterns());
            }
            if (!searchRules.getIncludeTokens().isEmpty()) {
                builder.field(INCLUDE_TOKENS_FIELD.getPreferredName(), searchRules.getIncludeTokens());
            }
            if (!searchRules.getIncludePrefixes().isEmpty()) {
                builder.field(INCLUDE_PREFIXES_FIELD.getPreferredName(), searchRules.getIncludePrefixes());
            }
            if (!searchRules.getIncludeSuffixes().isEmpty()) {
                builder.field(INCLUDE_SUFFIXES_FIELD.getPreferredName(), searchRules.getIncludeSuffixes());
            }
            if (!searchRules.getIncludeContains().isEmpty()) {
                builder.field(INCLUDE_CONTAINS_FIELD.getPreferredName(), searchRules.getIncludeContains());
            }
            if (!searchRules.getIncludePatterns().isEmpty()) {
                builder.field(INCLUDE_PATTERNS_FIELD.getPreferredName(), searchRules.getIncludePatterns());
            }
            if (!searchRules.getDecludePrefixes().isEmpty()) {
                builder.field(DECLUDE_PREFIXES_FIELD.getPreferredName(), searchRules.getDecludePrefixes());
            }
            if (!searchRules.getDecludeSuffixes().isEmpty()) {
                builder.field(DECLUDE_SUFFIXES_FIELD.getPreferredName(), searchRules.getDecludeSuffixes());
            }
            builder.endObject();
        }

        if (maxFreq > 0) {
            builder.field(MAX_FREQ_FIELD.getPreferredName(), maxFreq);
        }
        builder.field(MIN_KEPT_TOKENS_COUNT_FIELD.getPreferredName(), minKeptTokensCount);
        if (minKeptTokensRatio > 0.0f && minKeptTokensRatio < 1.0f) {
            builder.field(MIN_KEPT_TOKENS_RATIO_FIELD.getPreferredName(), minKeptTokensRatio);
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
        Map<String, Float> fieldsAndWeights = new HashMap<>();
        String defaultField = null;
        Operator defaultOperator = Operator.OR;
        String analyzer = null;
        String quoteAnalyzer = null;
        String quoteFieldSuffix = null;
        int phraseSlop = 0;
        Fuzziness fuzziness = Fuzziness.AUTO;
        int fuzzyPrefixLength = 1;
        int fuzzyMaxExpansions = 50;
        boolean fuzzyTranspositions = true;
        String fuzzyRewrite = null;
        Boolean lenient = null;
        Boolean analyzeWildcard = null;
        ZoneId timeZone = null;
        MultiMatchQueryBuilder.Type type = MultiMatchQueryBuilder.Type.BEST_FIELDS;
        Float tieBreaker = null;
        String rewrite = null;
        String minimumShouldMatch = null;
        boolean enablePositionIncrements = true;
        int maxDeterminizedStates = 10000;
        boolean autoGenerateSynonymsPhraseQuery = true;
        SearchRules searchRules = SearchRules.EMPTY;
        int maxFreq = 0;
        int minKeptTokensCount = 1;
        float minKeptTokensRatio = -1.0f;
        float boost = DEFAULT_BOOST;
        String queryName = null;

        String currentFieldName = null;
        XContentParser.Token token;

        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_OBJECT) {
                if (RULES_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    searchRules = parseRulesObject(parser);
                } else {
                    throw new ParsingException(parser.getTokenLocation(),
                            "[" + NAME + "] query does not support object [" + currentFieldName + "]");
                }
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
                } else if (QUOTE_FIELD_SUFFIX_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    quoteFieldSuffix = parser.text();
                } else if (PHRASE_SLOP_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    phraseSlop = parser.intValue();
                } else if (FUZZINESS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    fuzziness = Fuzziness.parse(parser);
                } else if (FUZZY_PREFIX_LENGTH_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    fuzzyPrefixLength = parser.intValue();
                } else if (FUZZY_MAX_EXPANSIONS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    fuzzyMaxExpansions = parser.intValue();
                } else if (FUZZY_TRANSPOSITIONS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    fuzzyTranspositions = parser.booleanValue();
                } else if (FUZZY_REWRITE_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    fuzzyRewrite = parser.text();
                } else if (LENIENT_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    lenient = parser.booleanValue();
                } else if (ANALYZE_WILDCARD_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    analyzeWildcard = parser.booleanValue();
                } else if (TIME_ZONE_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    timeZone = ZoneId.of(parser.text());
                } else if (TYPE_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    type = MultiMatchQueryBuilder.Type.parse(parser.text(), parser.getDeprecationHandler());
                } else if (TIE_BREAKER_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    tieBreaker = parser.floatValue();
                } else if (REWRITE_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    rewrite = parser.text();
                } else if (MINIMUM_SHOULD_MATCH_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    minimumShouldMatch = parser.text();
                } else if (ENABLE_POSITION_INCREMENTS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    enablePositionIncrements = parser.booleanValue();
                } else if (MAX_DETERMINIZED_STATES_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    maxDeterminizedStates = parser.intValue();
                } else if (AUTO_GENERATE_SYNONYMS_PHRASE_QUERY_FIELD.match(currentFieldName,
                        parser.getDeprecationHandler())) {
                    autoGenerateSynonymsPhraseQuery = parser.booleanValue();
                } else if (MAX_FREQ_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    maxFreq = parser.intValue();
                } else if (MIN_KEPT_TOKENS_COUNT_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    minKeptTokensCount = parser.intValue();
                } else if (MIN_KEPT_TOKENS_RATIO_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    minKeptTokensRatio = parser.floatValue();
                } else if (AbstractQueryBuilder.BOOST_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    boost = parser.floatValue();
                } else if (AbstractQueryBuilder.NAME_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    queryName = parser.text();
                } else {
                    throw new ParsingException(parser.getTokenLocation(),
                            "[" + NAME + "] query does not support [" + currentFieldName + "]");
                }
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
        queryBuilder.quoteFieldSuffix(quoteFieldSuffix);
        queryBuilder.phraseSlop(phraseSlop);
        queryBuilder.fuzziness(fuzziness);
        queryBuilder.fuzzyPrefixLength(fuzzyPrefixLength);
        queryBuilder.fuzzyMaxExpansions(fuzzyMaxExpansions);
        queryBuilder.fuzzyTranspositions(fuzzyTranspositions);
        queryBuilder.fuzzyRewrite(fuzzyRewrite);
        if (lenient != null) {
            queryBuilder.lenient(lenient);
        }
        if (analyzeWildcard != null) {
            queryBuilder.analyzeWildcard(analyzeWildcard);
        }
        if (timeZone != null) {
            queryBuilder.timeZone(timeZone);
        }
        if (type != null) {
            queryBuilder.type(type);
        }
        if (tieBreaker != null) {
            queryBuilder.tieBreaker(tieBreaker);
        }
        if (rewrite != null) {
            queryBuilder.rewrite(rewrite);
        }
        if (minimumShouldMatch != null) {
            queryBuilder.minimumShouldMatch(minimumShouldMatch);
        }
        queryBuilder.enablePositionIncrements(enablePositionIncrements);
        queryBuilder.maxDeterminizedStates(maxDeterminizedStates);
        queryBuilder.autoGenerateSynonymsPhraseQuery(autoGenerateSynonymsPhraseQuery);
        queryBuilder.searchRules(searchRules);
        queryBuilder.maxFreq(maxFreq);
        queryBuilder.minKeptTokensCount(minKeptTokensCount);
        queryBuilder.minKeptTokensRatio(minKeptTokensRatio);
        queryBuilder.boost(boost);
        queryBuilder.queryName(queryName);

        return queryBuilder;
    }

    /**
     * Parse the "rules" object from XContent.
     * Supports inline rules or file reference.
     */
    private static SearchRules parseRulesObject(XContentParser parser) throws IOException {
        List<String> excludeTokens = new ArrayList<>();
        List<String> excludePrefixes = new ArrayList<>();
        List<String> excludeSuffixes = new ArrayList<>();
        List<String> excludeContains = new ArrayList<>();
        List<String> excludePatterns = new ArrayList<>();
        List<String> includeTokens = new ArrayList<>();
        List<String> includePrefixes = new ArrayList<>();
        List<String> includeSuffixes = new ArrayList<>();
        List<String> includeContains = new ArrayList<>();
        List<String> includePatterns = new ArrayList<>();
        List<String> decludePrefixes = new ArrayList<>();
        List<String> decludeSuffixes = new ArrayList<>();
        String file = null;

        String currentFieldName = null;
        XContentParser.Token token;

        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_ARRAY) {
                List<String> targetList;
                if (EXCLUDE_TOKENS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    targetList = excludeTokens;
                } else if (EXCLUDE_PREFIXES_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    targetList = excludePrefixes;
                } else if (EXCLUDE_SUFFIXES_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    targetList = excludeSuffixes;
                } else if (EXCLUDE_CONTAINS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    targetList = excludeContains;
                } else if (EXCLUDE_PATTERNS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    targetList = excludePatterns;
                } else if (INCLUDE_TOKENS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    targetList = includeTokens;
                } else if (INCLUDE_PREFIXES_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    targetList = includePrefixes;
                } else if (INCLUDE_SUFFIXES_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    targetList = includeSuffixes;
                } else if (INCLUDE_CONTAINS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    targetList = includeContains;
                } else if (INCLUDE_PATTERNS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    targetList = includePatterns;
                } else if (DECLUDE_PREFIXES_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    targetList = decludePrefixes;
                } else if (DECLUDE_SUFFIXES_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    targetList = decludeSuffixes;
                } else {
                    throw new ParsingException(parser.getTokenLocation(),
                            "[rules] does not support array [" + currentFieldName + "]");
                }
                while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                    targetList.add(parser.text());
                }
            } else if (token.isValue()) {
                if (FILE_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    file = parser.text();
                } else {
                    throw new ParsingException(parser.getTokenLocation(),
                            "[rules] does not support [" + currentFieldName + "]");
                }
            }
        }

        // If file is specified, load from file
        if (file != null && !file.isEmpty()) {
            SearchRules fromFile = RulesLoader.loadFromFile(file);
            if (!fromFile.isEmpty()) {
                return fromFile;
            }
        }

        // Use inline rules
        return new SearchRules(excludeTokens, excludePrefixes, excludeSuffixes, excludeContains, excludePatterns,
                includeTokens, includePrefixes, includeSuffixes, includeContains, includePatterns,
                decludePrefixes, decludeSuffixes);
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
                && Objects.equals(defaultOperator, other.defaultOperator)
                && Objects.equals(analyzer, other.analyzer)
                && Objects.equals(quoteAnalyzer, other.quoteAnalyzer)
                && Objects.equals(quoteFieldSuffix, other.quoteFieldSuffix)
                && phraseSlop == other.phraseSlop
                && Objects.equals(fuzziness, other.fuzziness)
                && fuzzyPrefixLength == other.fuzzyPrefixLength
                && fuzzyMaxExpansions == other.fuzzyMaxExpansions
                && fuzzyTranspositions == other.fuzzyTranspositions
                && Objects.equals(fuzzyRewrite, other.fuzzyRewrite)
                && Objects.equals(lenient, other.lenient)
                && Objects.equals(analyzeWildcard, other.analyzeWildcard)
                && Objects.equals(timeZone, other.timeZone)
                && Objects.equals(type, other.type)
                && Objects.equals(tieBreaker, other.tieBreaker)
                && Objects.equals(rewrite, other.rewrite)
                && Objects.equals(minimumShouldMatch, other.minimumShouldMatch)
                && enablePositionIncrements == other.enablePositionIncrements
                && maxDeterminizedStates == other.maxDeterminizedStates
                && autoGenerateSynonymsPhraseQuery == other.autoGenerateSynonymsPhraseQuery
                && Objects.equals(searchRules, other.searchRules)
                && maxFreq == other.maxFreq
                && minKeptTokensCount == other.minKeptTokensCount
                && Float.compare(minKeptTokensRatio, other.minKeptTokensRatio) == 0;
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(queryString, fieldsAndWeights, defaultField, defaultOperator,
                analyzer, quoteAnalyzer, quoteFieldSuffix, phraseSlop, fuzziness,
                fuzzyPrefixLength, fuzzyMaxExpansions, fuzzyTranspositions, fuzzyRewrite,
                lenient, analyzeWildcard, timeZone, type, tieBreaker, rewrite,
                minimumShouldMatch, enablePositionIncrements, maxDeterminizedStates,
                autoGenerateSynonymsPhraseQuery, searchRules, maxFreq, minKeptTokensCount, minKeptTokensRatio);
    }
}
