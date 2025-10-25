package org.es.tok.query;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.MultiPhraseQuery;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.search.QueryStringQueryParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extended QueryStringQueryParser with token filtering capabilities
 */
public class EsTokQueryStringQueryParser extends QueryStringQueryParser {

    private List<String> ignoredTokens = new ArrayList<>();
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
        // Choose based on which parameters are provided
        super(context,
                (fieldsAndWeights != null && !fieldsAndWeights.isEmpty())
                        ? fieldsAndWeights
                        : Map.of(defaultField != null ? defaultField : "*", 1.0f),
                lenient);
        this.context = context;
    }

    public void setIgnoredTokens(List<String> ignoredTokens) {
        this.ignoredTokens = ignoredTokens != null ? new ArrayList<>(ignoredTokens) : new ArrayList<>();
    }

    public void setMaxFreq(int maxFreq) {
        this.maxFreq = maxFreq;
    }

    @Override
    public Query getFieldQuery(String field, String queryText, boolean quoted) throws ParseException {
        // Get the original query from parent
        Query originalQuery = super.getFieldQuery(field, queryText, quoted);

        if (originalQuery == null) {
            return null;
        }

        // Apply token filtering
        return filterQuery(originalQuery, field);
    }

    @Override
    protected Query getFieldQuery(String field, String queryText, int slop) throws ParseException {
        // Get the original query from parent
        Query originalQuery = super.getFieldQuery(field, queryText, slop);

        if (originalQuery == null) {
            return null;
        }

        // Apply token filtering
        return filterQuery(originalQuery, field);
    }

    /**
     * Filter query to remove ignored tokens and high-frequency tokens
     */
    private Query filterQuery(Query query, String field) {
        if (query == null || (ignoredTokens.isEmpty() && maxFreq <= 0)) {
            return query;
        }

        // Handle BooleanQuery recursively
        if (query instanceof BooleanQuery) {
            BooleanQuery boolQuery = (BooleanQuery) query;
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            boolean hasValidClause = false;

            for (BooleanClause clause : boolQuery.clauses()) {
                Query filteredSubQuery = filterQuery(clause.query(), field);
                if (filteredSubQuery != null && !(filteredSubQuery instanceof MatchNoDocsQuery)) {
                    builder.add(filteredSubQuery, clause.occur());
                    hasValidClause = true;
                }
            }

            if (!hasValidClause) {
                return new MatchNoDocsQuery();
            }

            return builder.build();
        }

        // Handle TermQuery
        if (query instanceof TermQuery) {
            TermQuery termQuery = (TermQuery) query;
            if (shouldFilterTerm(termQuery.getTerm())) {
                return new MatchNoDocsQuery();
            }
            return query;
        }

        // Handle PhraseQuery
        if (query instanceof PhraseQuery) {
            PhraseQuery phraseQuery = (PhraseQuery) query;
            Term[] terms = phraseQuery.getTerms();
            for (Term term : terms) {
                if (shouldFilterTerm(term)) {
                    // If any term in phrase should be filtered, filter the whole phrase
                    return new MatchNoDocsQuery();
                }
            }
            return query;
        }

        // Handle MultiPhraseQuery
        if (query instanceof MultiPhraseQuery) {
            MultiPhraseQuery multiPhraseQuery = (MultiPhraseQuery) query;
            Term[][] termArrays = multiPhraseQuery.getTermArrays();
            for (Term[] terms : termArrays) {
                for (Term term : terms) {
                    if (shouldFilterTerm(term)) {
                        return new MatchNoDocsQuery();
                    }
                }
            }
            return query;
        }

        // For other query types, return as is
        return query;
    }

    /**
     * Check if a term should be filtered based on ignored tokens and max frequency
     */
    private boolean shouldFilterTerm(Term term) {
        String termText = term.text();

        // Check against ignored tokens list
        if (ignoredTokens.contains(termText)) {
            return true;
        }

        // Check against max frequency threshold
        if (maxFreq > 0) {
            try {
                IndexReader reader = context.getIndexReader();
                if (reader != null) {
                    long docFreq = reader.docFreq(term);
                    if (docFreq > maxFreq) {
                        return true;
                    }
                }
            } catch (IOException e) {
                // If we can't get doc frequency, don't filter
                return false;
            }
        }

        return false;
    }
}
