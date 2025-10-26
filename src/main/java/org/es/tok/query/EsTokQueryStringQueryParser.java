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
        return super.getFieldQuery(field, queryText, quoted);
    }

    @Override
    protected Query getFieldQuery(String field, String queryText, int slop) throws ParseException {
        return super.getFieldQuery(field, queryText, slop);
    }

    @Override
    public Query parse(String query) throws ParseException {
        Query originalQuery = super.parse(query);
        if (originalQuery == null) {
            return null;
        }
        return filterQueryWithTopLevelMinKept(originalQuery, null);
    }

    /**
     * Apply token filtering with min_kept_tokens protection
     */
    private Query filterQueryWithTopLevelMinKept(Query query, String field) {
        if (query == null || (ignoredTokens.isEmpty() && maxFreq <= 0)) {
            return query;
        }

        if (shouldSkipFilteringDueToMinKept(query)) {
            return query;
        }

        return filterQuery(query, field);
    }

    /**
     * Check if filtering should be skipped to maintain minimum token count
     */
    private boolean shouldSkipFilteringDueToMinKept(Query query) {
        if (minKeptTokensCount <= 0 && (minKeptTokensRatio <= 0.0f || minKeptTokensRatio >= 1.0f)) {
            return false;
        }

        List<Term> allTerms = extractUniqueTermsFromQuery(query);
        if (allTerms.isEmpty()) {
            return false;
        }

        List<Term> allTermsAllFields = extractTermsFromQuery(query);
        int totalTerms = allTerms.size();
        int wouldBeFiltered = 0;

        for (Term uniqueTerm : allTerms) {
            String text = uniqueTerm.text();
            boolean shouldFilterThisText = false;

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
     * Extract unique terms by text (deduplicated across fields)
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
     * Recursively filter query tree
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

        return filterAtomicQuery(query);
    }

    /**
     * Filter BooleanQuery clauses
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
     * Filter DisjunctionMaxQuery disjuncts
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
     * Extract all terms from query (including quoted strings)
     */
    private List<Term> extractTermsFromQuery(Query query) {
        List<Term> terms = new ArrayList<>();

        if (query instanceof TermQuery) {
            terms.add(((TermQuery) query).getTerm());
        } else if (query instanceof BlendedTermQuery) {
            terms.addAll(((BlendedTermQuery) query).getTerms());
        } else if (query instanceof PhraseQuery || query instanceof MultiPhraseQuery) {
            // Skip phrase query terms (quoted strings not counted in min_kept)
            return terms;
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
     * Filter atomic queries (TermQuery, BlendedTermQuery)
     */
    private Query filterAtomicQuery(Query query) {
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

        if (query instanceof TermQuery) {
            TermQuery termQuery = (TermQuery) query;
            if (shouldFilterTermBasic(termQuery.getTerm())) {
                return new MatchNoDocsQuery();
            }
            return query;
        }

        // Preserve phrase queries (quoted strings)
        if (query instanceof PhraseQuery || query instanceof MultiPhraseQuery) {
            return query;
        }

        return query;
    }

    /**
     * Check if term should be filtered based on ignored list or frequency
     */
    private boolean shouldFilterTermBasic(Term term) {
        String termText = term.text();

        if (ignoredTokens.contains(termText)) {
            return true;
        }

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
                return false;
            }
        }

        return false;
    }
}
