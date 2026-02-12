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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private int maxFreq = 0;
    private SearchExecutionContext context;

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

    @Override
    public Query parse(String query) throws ParseException {
        Query originalQuery = super.parse(query);
        if (originalQuery == null || maxFreq <= 0) {
            return originalQuery;
        }
        return filterQuery(originalQuery);
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
}
