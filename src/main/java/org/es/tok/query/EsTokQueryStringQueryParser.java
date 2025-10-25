package org.es.tok.query;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.MultiPhraseQuery;
import org.apache.lucene.search.BoostQuery;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.search.QueryStringQueryParser;
import org.elasticsearch.lucene.queries.BlendedTermQuery;

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
    private int minKeptTokensCount = 1; // Default: keep at least 1 token
    private float minKeptTokensRatio = -1.0f; // Default: disabled
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

    public void setMinKeptTokensCount(int minKeptTokensCount) {
        this.minKeptTokensCount = minKeptTokensCount;
    }

    public void setMinKeptTokensRatio(float minKeptTokensRatio) {
        this.minKeptTokensRatio = minKeptTokensRatio;
    }

    @Override
    public Query getFieldQuery(String field, String queryText, boolean quoted) throws ParseException {
        // Get the original query from parent - DO NOT filter here
        // Filtering will be done at top level in parse() method
        Query originalQuery = super.getFieldQuery(field, queryText, quoted);
        return originalQuery;
    }

    @Override
    protected Query getFieldQuery(String field, String queryText, int slop) throws ParseException {
        // Get the original query from parent - DO NOT filter here
        // Filtering will be done at top level in parse() method
        Query originalQuery = super.getFieldQuery(field, queryText, slop);
        return originalQuery;
    }

    @Override
    public Query parse(String query) throws ParseException {
        // Get the original query from parent
        Query originalQuery = super.parse(query);

        if (originalQuery == null) {
            return null;
        }

        // Apply deep filtering to the entire query tree
        Query filteredQuery = filterQueryWithTopLevelMinKept(originalQuery, null);

        return filteredQuery;
    }

    /**
     * Top-level filter with min_kept_tokens logic
     */
    private Query filterQueryWithTopLevelMinKept(Query query, String field) {
        if (query == null || (ignoredTokens.isEmpty() && maxFreq <= 0)) {
            return query;
        }

        Query filteredQuery = filterQuery(query, field);

        // If completely filtered, check if min_kept requires keeping the original query
        if (filteredQuery instanceof MatchNoDocsQuery) {
            if (shouldKeepQueryDueToMinKept(query)) {
                return query;
            }
        }

        return filteredQuery;
    }

    /**
     * Check if query should be kept due to min_kept_tokens constraints
     */
    private boolean shouldKeepQueryDueToMinKept(Query query) {
        if (minKeptTokensCount <= 0 && (minKeptTokensRatio <= 0.0f || minKeptTokensRatio >= 1.0f)) {
            return false;
        }

        List<Term> allTerms = extractUniqueTermsFromQuery(query);
        if (allTerms.isEmpty()) {
            return false;
        }

        // Check if each unique text would be filtered in any field
        // filterAtomicQuery filters the entire query if any term should be filtered
        List<Term> allTermsAllFields = extractTermsFromQuery(query);
        int totalTerms = allTerms.size();
        int wouldBeFiltered = 0;

        for (Term uniqueTerm : allTerms) {
            String text = uniqueTerm.text();
            boolean shouldFilterThisText = false;

            // Check if this text should be filtered in any field
            for (Term term : allTermsAllFields) {
                if (term.text().equals(text) && shouldFilterTermBasic(term)) {
                    shouldFilterThisText = true;
                    break;
                }
            }

            if (shouldFilterThisText) {
                wouldBeFiltered++;
            }
        }

        int wouldBeKept = totalTerms - wouldBeFiltered;

        // Calculate minimum tokens to keep
        int minToKeepByCount = (minKeptTokensCount > 0) ? minKeptTokensCount : 0;
        int minToKeepByRatio = 0;
        if (minKeptTokensRatio > 0.0f && minKeptTokensRatio < 1.0f) {
            minToKeepByRatio = Math.max(1, (int) Math.ceil(totalTerms * minKeptTokensRatio));
        }
        int minToKeep = Math.max(minToKeepByCount, minToKeepByRatio);
        int effectiveMinToKeep = Math.min(minToKeep, totalTerms);

        return wouldBeKept < effectiveMinToKeep;
    }

    /**
     * Extract unique terms deduplicated by text only
     * e.g., title.words:"影视" and tags.words:"影视" count as one unique token
     */
    private List<Term> extractUniqueTermsFromQuery(Query query) {
        List<Term> allTerms = extractTermsFromQuery(query);
        List<Term> uniqueTerms = new ArrayList<>();

        for (Term term : allTerms) {
            boolean isDuplicate = false;
            for (Term existing : uniqueTerms) {
                if (existing.text().equals(term.text())) {
                    isDuplicate = true;
                    break;
                }
            }
            if (!isDuplicate) {
                uniqueTerms.add(term);
            }
        }

        return uniqueTerms;
    }

    /**
     * Filter query recursively (min_kept logic handled at top level only)
     */
    private Query filterQuery(Query query, String field) {
        if (query == null || (ignoredTokens.isEmpty() && maxFreq <= 0)) {
            return query;
        }

        if (query instanceof BooleanQuery) {
            return filterBooleanQuerySimple((BooleanQuery) query, field);
        }

        if (query instanceof DisjunctionMaxQuery) {
            return filterDisjunctionMaxQuerySimple((DisjunctionMaxQuery) query, field);
        }

        if (query instanceof BoostQuery) {
            BoostQuery boostQuery = (BoostQuery) query;
            Query innerQuery = boostQuery.getQuery();
            Query filteredInner = filterQuery(innerQuery, field);

            if (filteredInner == null || filteredInner instanceof MatchNoDocsQuery) {
                return new MatchNoDocsQuery();
            }

            return new BoostQuery(filteredInner, boostQuery.getBoost());
        }

        // For atomic queries, apply strict filtering
        return filterAtomicQuery(query);
    }

    /**
     * Simple filtering for BooleanQuery
     */
    private Query filterBooleanQuerySimple(BooleanQuery boolQuery, String field) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        boolean hasValidClause = false;

        for (BooleanClause clause : boolQuery.clauses()) {
            Query filtered = filterQuery(clause.query(), field);
            if (filtered != null && !(filtered instanceof MatchNoDocsQuery)) {
                builder.add(filtered, clause.occur());
                hasValidClause = true;
            }
        }

        if (!hasValidClause) {
            return new MatchNoDocsQuery();
        }

        return builder.build();
    }

    /**
     * Simple filtering for DisjunctionMaxQuery
     */
    private Query filterDisjunctionMaxQuerySimple(DisjunctionMaxQuery disMaxQuery, String field) {
        List<Query> filteredDisjuncts = new ArrayList<>();
        for (Query disjunct : disMaxQuery.getDisjuncts()) {
            Query filtered = filterQuery(disjunct, field);
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

    /**
     * Extract all terms from query recursively
     */
    private List<Term> extractTermsFromQuery(Query query) {
        List<Term> terms = new ArrayList<>();

        if (query instanceof TermQuery) {
            terms.add(((TermQuery) query).getTerm());
        } else if (query instanceof BlendedTermQuery) {
            terms.addAll(((BlendedTermQuery) query).getTerms());
        } else if (query instanceof PhraseQuery) {
            for (Term term : ((PhraseQuery) query).getTerms()) {
                terms.add(term);
            }
        } else if (query instanceof MultiPhraseQuery) {
            Term[][] termArrays = ((MultiPhraseQuery) query).getTermArrays();
            for (Term[] termArray : termArrays) {
                for (Term term : termArray) {
                    terms.add(term);
                }
            }
        } else if (query instanceof BoostQuery) {
            terms.addAll(extractTermsFromQuery(((BoostQuery) query).getQuery()));
        } else if (query instanceof BooleanQuery) {
            for (BooleanClause clause : ((BooleanQuery) query).clauses()) {
                terms.addAll(extractTermsFromQuery(clause.query()));
            }
        } else if (query instanceof DisjunctionMaxQuery) {
            for (Query disjunct : ((DisjunctionMaxQuery) query).getDisjuncts()) {
                terms.addAll(extractTermsFromQuery(disjunct));
            }
        }

        return terms;
    }

    /**
     * Filter atomic queries (if any term should be filtered, filter entire query)
     */
    private Query filterAtomicQuery(Query query) {
        // Handle BlendedTermQuery
        if (query instanceof BlendedTermQuery) {
            BlendedTermQuery blendedQuery = (BlendedTermQuery) query;
            List<Term> terms = blendedQuery.getTerms();
            for (Term term : terms) {
                if (shouldFilterTermBasic(term)) {
                    return new MatchNoDocsQuery();
                }
            }
            return query;
        }

        // Handle TermQuery
        if (query instanceof TermQuery) {
            TermQuery termQuery = (TermQuery) query;
            if (shouldFilterTermBasic(termQuery.getTerm())) {
                return new MatchNoDocsQuery();
            }
            return query;
        }

        // Handle PhraseQuery
        if (query instanceof PhraseQuery) {
            PhraseQuery phraseQuery = (PhraseQuery) query;
            Term[] terms = phraseQuery.getTerms();
            for (Term term : terms) {
                if (shouldFilterTermBasic(term)) {
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
                    if (shouldFilterTermBasic(term)) {
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
     * Check if a term should be filtered (ignoring min_kept constraints)
     */
    private boolean shouldFilterTermBasic(Term term) {
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
