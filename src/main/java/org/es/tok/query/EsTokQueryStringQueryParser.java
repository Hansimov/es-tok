package org.es.tok.query;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.MultiPhraseQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.search.QueryStringQueryParser;
import org.elasticsearch.lucene.queries.BlendedTermQuery;
import org.es.tok.suggest.LuceneIndexSuggester;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Extended QueryStringQueryParser with frequency-based token filtering.
 * <p>
 * When {@code max_freq} is set, terms whose document frequency exceeds the
 * threshold are filtered out of the parsed query (dynamic stopwords).
 * <p>
 * Document-level constraint filtering is handled separately by
 * {@link ConstraintBuilder} in the query builder layer, not in the parser.
 */
public class EsTokQueryStringQueryParser extends QueryStringQueryParser {

    private static final String RESERVED_QUERY_CHARS = "+-!():{}[]^\"~*?:\\/|&";
    private static final Pattern BOOLEAN_KEYWORD_PATTERN = Pattern.compile("(^|\\s)(AND|OR|NOT)(\\s|$)");

    private int maxFreq = 0;
    private SearchExecutionContext context;
    private LuceneIndexSuggester.CorrectionConfig correctionConfig;
    private List<String> suggestionFields = Collections.emptyList();
    private LuceneIndexSuggester suggester;

    public EsTokQueryStringQueryParser(SearchExecutionContext context, String defaultField) {
        super(context, defaultField);
        this.context = context;
    }

    public EsTokQueryStringQueryParser(SearchExecutionContext context, String defaultField, boolean lenient) {
        super(context, defaultField, lenient);
        this.context = context;
    }

    public EsTokQueryStringQueryParser(SearchExecutionContext context, Map<String, Float> fieldsAndWeights) {
        super(context, fieldsAndWeights);
        this.context = context;
    }

    public EsTokQueryStringQueryParser(SearchExecutionContext context, Map<String, Float> fieldsAndWeights,
            boolean lenient) {
        super(context, fieldsAndWeights, lenient);
        this.context = context;
    }

    public EsTokQueryStringQueryParser(
            SearchExecutionContext context,
            String defaultField,
            Map<String, Float> fieldsAndWeights,
            boolean lenient) {
        super(context,
                (fieldsAndWeights != null && !fieldsAndWeights.isEmpty())
                        ? fieldsAndWeights
                        : Map.of(defaultField != null ? defaultField : "*", 1.0f),
                lenient);
        this.context = context;
    }

    public void setMaxFreq(int maxFreq) {
        this.maxFreq = maxFreq;
    }

    public void setCorrectionConfig(LuceneIndexSuggester.CorrectionConfig correctionConfig) {
        this.correctionConfig = correctionConfig;
    }

    public void setSuggestionFields(List<String> suggestionFields) {
        this.suggestionFields = suggestionFields != null ? List.copyOf(suggestionFields) : Collections.emptyList();
    }

    @Override
    public Query parse(String query) throws ParseException {
        boolean useRawCorrection = correctionConfig != null
                && !suggestionFields.isEmpty()
                && shouldCorrectRawQuery(query);
        String effectiveQuery = useRawCorrection ? correctSimpleQueryText(query) : query;

        Query originalQuery = super.parse(effectiveQuery);
        if (originalQuery == null) {
            return originalQuery;
        }

        if (!useRawCorrection && correctionConfig != null && !suggestionFields.isEmpty()) {
            originalQuery = correctQuery(originalQuery);
        }
        if (maxFreq > 0) {
            originalQuery = filterQuery(originalQuery);
        }
        return originalQuery;
    }

    private boolean shouldCorrectRawQuery(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        if (BOOLEAN_KEYWORD_PATTERN.matcher(query).find()) {
            return false;
        }
        for (int i = 0; i < query.length(); i++) {
            if (RESERVED_QUERY_CHARS.indexOf(query.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    private String correctSimpleQueryText(String query) {
        try {
            List<LuceneIndexSuggester.SuggestionOption> suggestions = getSuggester()
                    .suggestCorrections(suggestionFields, query, correctionConfig);
            if (suggestions.isEmpty()) {
                return query;
            }
            return suggestions.get(0).text();
        } catch (IOException e) {
            return query;
        }
    }

    private Query correctQuery(Query query) {
        if (query == null) {
            return null;
        }

        if (query instanceof BooleanQuery boolQuery) {
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            for (BooleanClause clause : boolQuery.clauses()) {
                Query corrected = correctQuery(clause.query());
                if (corrected != null) {
                    builder.add(corrected, clause.occur());
                }
            }
            return builder.build();
        }
        if (query instanceof DisjunctionMaxQuery disjunctionMaxQuery) {
            List<Query> corrected = new ArrayList<>(disjunctionMaxQuery.getDisjuncts().size());
            for (Query disjunct : disjunctionMaxQuery.getDisjuncts()) {
                Query correctedDisjunct = correctQuery(disjunct);
                if (correctedDisjunct != null) {
                    corrected.add(correctedDisjunct);
                }
            }
            return corrected.isEmpty()
                    ? query
                    : new DisjunctionMaxQuery(corrected, disjunctionMaxQuery.getTieBreakerMultiplier());
        }
        if (query instanceof BoostQuery boostQuery) {
            Query corrected = correctQuery(boostQuery.getQuery());
            return corrected == null ? null : new BoostQuery(corrected, boostQuery.getBoost());
        }
        if (query instanceof TermQuery termQuery) {
            return correctTermQuery(termQuery);
        }
        if (query instanceof PhraseQuery phraseQuery) {
            return correctPhraseQuery(phraseQuery);
        }
        if (query instanceof MultiPhraseQuery) {
            return query;
        }
        if (query instanceof BlendedTermQuery) {
            return query;
        }
        return query;
    }

    // ===== Frequency-based filtering =====

    /**
     * Recursively filter query tree, removing terms that exceed maxFreq.
     */
    private Query filterQuery(Query query) {
        if (query == null) {
            return null;
        }

        if (query instanceof BooleanQuery boolQuery) {
            return filterBooleanQuery(boolQuery);
        }
        if (query instanceof DisjunctionMaxQuery disMaxQuery) {
            return filterDisjunctionMaxQuery(disMaxQuery);
        }
        if (query instanceof BoostQuery boostQuery) {
            Query filtered = filterQuery(boostQuery.getQuery());
            if (filtered == null || filtered instanceof MatchNoDocsQuery) {
                return new MatchNoDocsQuery();
            }
            return new BoostQuery(filtered, boostQuery.getBoost());
        }

        return filterAtomicQuery(query);
    }

    private Query filterBooleanQuery(BooleanQuery boolQuery) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        boolean hasValidClause = false;

        for (BooleanClause clause : boolQuery.clauses()) {
            Query filtered = filterQuery(clause.query());
            if (filtered != null && !(filtered instanceof MatchNoDocsQuery)) {
                builder.add(filtered, clause.occur());
                hasValidClause = true;
            }
        }

        return hasValidClause ? builder.build() : new MatchNoDocsQuery();
    }

    private Query filterDisjunctionMaxQuery(DisjunctionMaxQuery disMaxQuery) {
        List<Query> filteredDisjuncts = new ArrayList<>();
        for (Query disjunct : disMaxQuery.getDisjuncts()) {
            Query filtered = filterQuery(disjunct);
            if (filtered != null && !(filtered instanceof MatchNoDocsQuery)) {
                filteredDisjuncts.add(filtered);
            }
        }

        if (filteredDisjuncts.isEmpty()) {
            return new MatchNoDocsQuery();
        }
        if (filteredDisjuncts.size() == 1) {
            return filteredDisjuncts.get(0);
        }
        return new DisjunctionMaxQuery(filteredDisjuncts, disMaxQuery.getTieBreakerMultiplier());
    }

    private Query filterAtomicQuery(Query query) {
        if (query instanceof BlendedTermQuery blendedQuery) {
            for (Term term : blendedQuery.getTerms()) {
                if (exceedsMaxFreq(term)) {
                    return new MatchNoDocsQuery();
                }
            }
            return query;
        }

        if (query instanceof TermQuery termQuery) {
            return exceedsMaxFreq(termQuery.getTerm()) ? new MatchNoDocsQuery() : query;
        }

        // Preserve phrase queries (quoted strings)
        if (query instanceof PhraseQuery || query instanceof MultiPhraseQuery) {
            return query;
        }

        return query;
    }

    /**
     * Check if a term's document frequency exceeds the maxFreq threshold.
     */
    private boolean exceedsMaxFreq(Term term) {
        try {
            IndexReader reader = context.getIndexReader();
            if (reader != null) {
                long docFreq = reader.docFreq(term);
                return docFreq > maxFreq;
            }
        } catch (IOException e) {
            // On error, don't filter the term
        }
        return false;
    }

    private Query correctTermQuery(TermQuery termQuery) {
        Term originalTerm = termQuery.getTerm();
        String correctedText = correctTermText(originalTerm.field(), originalTerm.text());
        if (correctedText.equals(originalTerm.text())) {
            return termQuery;
        }
        return new TermQuery(new Term(originalTerm.field(), correctedText));
    }

    private Query correctPhraseQuery(PhraseQuery phraseQuery) {
        Term[] originalTerms = phraseQuery.getTerms();
        int[] positions = phraseQuery.getPositions();
        PhraseQuery.Builder builder = new PhraseQuery.Builder();
        builder.setSlop(phraseQuery.getSlop());

        boolean changed = false;
        for (int i = 0; i < originalTerms.length; i++) {
            Term originalTerm = originalTerms[i];
            String correctedText = correctTermText(originalTerm.field(), originalTerm.text());
            changed |= correctedText.equals(originalTerm.text()) == false;
            builder.add(new Term(originalTerm.field(), correctedText), positions[i]);
        }

        return changed ? builder.build() : phraseQuery;
    }

    private String correctTermText(String field, String text) {
        try {
            List<String> fields = field != null ? List.of(field) : suggestionFields;
            LuceneIndexSuggester.Correction correction = getSuggester().suggestCorrection(fields, text, correctionConfig);
            return correction.changed() ? correction.suggested() : text;
        } catch (IOException e) {
            return text;
        }
    }

    private LuceneIndexSuggester getSuggester() {
        if (suggester == null) {
            suggester = new LuceneIndexSuggester(context.getIndexReader());
        }
        return suggester;
    }
}
