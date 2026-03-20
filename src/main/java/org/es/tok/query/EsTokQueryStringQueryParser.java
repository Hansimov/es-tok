package org.es.tok.query;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.es.tok.suggest.LuceneIndexSuggester;
import org.es.tok.text.TextNormalization;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal parser for {@code es_tok_query_string}.
 * <p>
 * Supported syntax is intentionally small:
 * <ul>
 * <li>whitespace-delimited natural language segments</li>
 * <li>{@code +segment} for required exact-token matching</li>
 * <li>{@code -segment} for excluded exact-token matching</li>
 * <li>{@code "segment"} for exact-token / exact-phrase matching</li>
 * </ul>
 * <p>
 * No wildcard, regex, boolean keywords, field scoping, or other Lucene
 * query-string operators are supported.
 */
public class EsTokQueryStringQueryParser {

    private static final KeywordAnalyzer KEYWORD_ANALYZER = new KeywordAnalyzer();

    private final SearchExecutionContext context;
    private final String defaultField;
    private final Map<String, Float> fieldsAndWeights;

    private Operator defaultOperator = Operator.OR;
    private Analyzer forceAnalyzer;
    private Analyzer forceQuoteAnalyzer;
    private int phraseSlop = 0;
    private float tieBreaker = 0.0f;
    private int maxFreq = 0;
    private LuceneIndexSuggester.CorrectionConfig correctionConfig;
    private List<String> suggestionFields = Collections.emptyList();
    private LuceneIndexSuggester suggester;

    public EsTokQueryStringQueryParser(
            SearchExecutionContext context,
            String defaultField,
            Map<String, Float> fieldsAndWeights) {
        this.context = context;
        this.defaultField = defaultField;
        this.fieldsAndWeights = fieldsAndWeights != null ? new LinkedHashMap<>(fieldsAndWeights) : Map.of();
    }

    public void setDefaultOperator(Operator defaultOperator) {
        this.defaultOperator = defaultOperator != null ? defaultOperator : Operator.OR;
    }

    public void setForceAnalyzer(Analyzer forceAnalyzer) {
        this.forceAnalyzer = forceAnalyzer;
    }

    public void setForceQuoteAnalyzer(Analyzer forceQuoteAnalyzer) {
        this.forceQuoteAnalyzer = forceQuoteAnalyzer;
    }

    public void setPhraseSlop(int phraseSlop) {
        this.phraseSlop = Math.max(0, phraseSlop);
    }

    public void setTieBreaker(Float tieBreaker) {
        this.tieBreaker = tieBreaker != null ? tieBreaker : 0.0f;
    }

    public void setMaxFreq(int maxFreq) {
        this.maxFreq = Math.max(0, maxFreq);
    }

    public void setCorrectionConfig(LuceneIndexSuggester.CorrectionConfig correctionConfig) {
        this.correctionConfig = correctionConfig;
    }

    public void setSuggestionFields(List<String> suggestionFields) {
        this.suggestionFields = suggestionFields != null ? List.copyOf(suggestionFields) : Collections.emptyList();
    }

    public Query parse(String queryText) throws IOException {
        List<ParsedClause> clauses = parseClauses(queryText);
        if (clauses.isEmpty()) {
            return new MatchNoDocsQuery();
        }

        clauses = maybeCorrectClauses(queryText, clauses);

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        int positiveClauseCount = 0;
        int addedClauseCount = 0;

        for (ParsedClause clause : clauses) {
            Query clauseQuery = buildClauseQuery(clause);
            if (clauseQuery == null || clauseQuery instanceof MatchNoDocsQuery) {
                continue;
            }

            BooleanClause.Occur occur = clause.occur() != null
                    ? clause.occur()
                    : (defaultOperator == Operator.AND ? BooleanClause.Occur.MUST : BooleanClause.Occur.SHOULD);
            builder.add(clauseQuery, occur);
            addedClauseCount++;
            if (occur != BooleanClause.Occur.MUST_NOT) {
                positiveClauseCount++;
            }
        }

        if (addedClauseCount == 0) {
            return new MatchNoDocsQuery();
        }
        if (positiveClauseCount == 0) {
            builder.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
        }
        return builder.build();
    }

    static List<ParsedClause> parseClauses(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        List<ParsedClause> clauses = new ArrayList<>();
        int index = 0;
        while (index < queryText.length()) {
            while (index < queryText.length() && Character.isWhitespace(queryText.charAt(index))) {
                index++;
            }
            if (index >= queryText.length()) {
                break;
            }

            BooleanClause.Occur occur = null;
            char current = queryText.charAt(index);
            if (current == '+' || current == '-') {
                occur = current == '+' ? BooleanClause.Occur.MUST : BooleanClause.Occur.MUST_NOT;
                index++;
                while (index < queryText.length() && Character.isWhitespace(queryText.charAt(index))) {
                    index++;
                }
                if (index >= queryText.length()) {
                    break;
                }
            }

            boolean exact = false;
            StringBuilder text = new StringBuilder();
            if (queryText.charAt(index) == '"') {
                exact = true;
                index++;
                boolean escaped = false;
                while (index < queryText.length()) {
                    char ch = queryText.charAt(index++);
                    if (escaped) {
                        text.append(ch);
                        escaped = false;
                        continue;
                    }
                    if (ch == '\\') {
                        escaped = true;
                        continue;
                    }
                    if (ch == '"') {
                        break;
                    }
                    text.append(ch);
                }
            } else {
                while (index < queryText.length() && Character.isWhitespace(queryText.charAt(index)) == false) {
                    char ch = queryText.charAt(index++);
                    if (ch == '\\' && index < queryText.length()) {
                        text.append(queryText.charAt(index++));
                        continue;
                    }
                    text.append(ch);
                }
            }

            String segment = text.toString().trim();
            if (segment.isBlank()) {
                continue;
            }
            clauses.add(new ParsedClause(segment, occur, exact || occur != null));
        }

        return clauses;
    }

    private List<ParsedClause> maybeCorrectClauses(String originalQuery, List<ParsedClause> clauses) {
        if (correctionConfig == null || suggestionFields.isEmpty()) {
            return clauses;
        }

        boolean simpleNaturalLanguage = clauses.stream().allMatch(clause -> clause.occur() == null && clause.exact() == false);
        if (simpleNaturalLanguage) {
            String corrected = correctText(originalQuery);
            if (corrected.equals(originalQuery)) {
                return clauses;
            }
            return parseClauses(corrected);
        }

        List<ParsedClause> correctedClauses = new ArrayList<>(clauses.size());
        for (ParsedClause clause : clauses) {
            if (clause.exact()) {
                correctedClauses.add(clause);
            } else {
                correctedClauses.add(clause.withText(correctText(clause.text())));
            }
        }
        return correctedClauses;
    }

    private Query buildClauseQuery(ParsedClause clause) throws IOException {
        List<FieldSpec> fieldSpecs = resolveFieldSpecs();
        if (fieldSpecs.isEmpty()) {
            return null;
        }

        List<Query> perFieldQueries = new ArrayList<>();
        for (FieldSpec fieldSpec : fieldSpecs) {
            Query fieldQuery = clause.exact()
                    ? buildExactFieldQuery(fieldSpec, clause.text())
                    : buildLooseFieldQuery(fieldSpec, clause.text());
            if (fieldQuery == null || fieldQuery instanceof MatchNoDocsQuery) {
                continue;
            }
            if (fieldSpec.boost() != 1.0f) {
                fieldQuery = new BoostQuery(fieldQuery, fieldSpec.boost());
            }
            perFieldQueries.add(fieldQuery);
        }

        if (perFieldQueries.isEmpty()) {
            return null;
        }
        if (perFieldQueries.size() == 1) {
            return perFieldQueries.get(0);
        }
        return new DisjunctionMaxQuery(perFieldQueries, tieBreaker);
    }

    private Query buildLooseFieldQuery(FieldSpec fieldSpec, String text) throws IOException {
        List<AnalyzedToken> analyzedTokens = analyze(resolveAnalyzer(fieldSpec.field(), false), fieldSpec.field(), text);
        if (analyzedTokens.isEmpty()) {
            return null;
        }

        Set<String> uniqueTerms = new LinkedHashSet<>();
        for (AnalyzedToken token : analyzedTokens) {
            if (token.isUsable() == false) {
                continue;
            }
            if (maxFreq > 0 && exceedsMaxFreq(fieldSpec.field(), token.term())) {
                continue;
            }
            uniqueTerms.add(token.term());
        }

        if (uniqueTerms.isEmpty()) {
            return null;
        }
        if (uniqueTerms.size() == 1) {
            return new TermQuery(new Term(fieldSpec.field(), uniqueTerms.iterator().next()));
        }

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String term : uniqueTerms) {
            builder.add(new TermQuery(new Term(fieldSpec.field(), term)), BooleanClause.Occur.SHOULD);
        }
        return builder.build();
    }

    private Query buildExactFieldQuery(FieldSpec fieldSpec, String text) throws IOException {
        List<AnalyzedToken> analyzedTokens = analyze(resolveAnalyzer(fieldSpec.field(), true), fieldSpec.field(), text);
        if (analyzedTokens.isEmpty()) {
            return null;
        }

        List<Query> alternatives = new ArrayList<>();
        int textLength = text.length();
        Set<String> fullSpanTerms = new LinkedHashSet<>();
        for (AnalyzedToken token : analyzedTokens) {
            if (token.isUsable() && token.startOffset() == 0 && token.endOffset() == textLength) {
                fullSpanTerms.add(token.term());
            }
        }
        for (String term : fullSpanTerms) {
            alternatives.add(new TermQuery(new Term(fieldSpec.field(), term)));
        }

        List<AnalyzedToken> phraseTokens = buildPhraseTokens(analyzedTokens);
        if (phraseTokens.size() == 1) {
            String onlyTerm = phraseTokens.get(0).term();
            if (fullSpanTerms.contains(onlyTerm) == false) {
                alternatives.add(new TermQuery(new Term(fieldSpec.field(), onlyTerm)));
            }
        } else if (phraseTokens.size() > 1) {
            PhraseQuery.Builder phraseBuilder = new PhraseQuery.Builder();
            phraseBuilder.setSlop(phraseSlop);
            for (AnalyzedToken token : phraseTokens) {
                phraseBuilder.add(new Term(fieldSpec.field(), token.term()), token.position());
            }
            alternatives.add(phraseBuilder.build());
        }

        if (alternatives.isEmpty()) {
            return null;
        }
        if (alternatives.size() == 1) {
            return alternatives.get(0);
        }

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (Query alternative : alternatives) {
            builder.add(alternative, BooleanClause.Occur.SHOULD);
        }
        return builder.build();
    }

    private List<AnalyzedToken> buildPhraseTokens(List<AnalyzedToken> analyzedTokens) {
        Map<Integer, AnalyzedToken> bestByStart = new LinkedHashMap<>();
        analyzedTokens.stream()
                .filter(AnalyzedToken::isUsable)
                .sorted(Comparator.comparingInt(AnalyzedToken::startOffset)
                        .thenComparing(Comparator.comparingInt(AnalyzedToken::endOffset).reversed())
                        .thenComparing(AnalyzedToken::term))
                .forEach(token -> {
                    AnalyzedToken existing = bestByStart.get(token.startOffset());
                    if (existing == null || token.endOffset() > existing.endOffset()) {
                        bestByStart.put(token.startOffset(), token);
                    }
                });

        List<AnalyzedToken> phraseTerms = new ArrayList<>();
        int previousEnd = -1;
        for (AnalyzedToken token : bestByStart.values()) {
            if (token.startOffset() < previousEnd) {
                continue;
            }
            phraseTerms.add(token);
            previousEnd = token.endOffset();
        }
        return phraseTerms;
    }

    private List<FieldSpec> resolveFieldSpecs() {
        Map<String, Float> rawFields = new LinkedHashMap<>();
        if (fieldsAndWeights.isEmpty() == false) {
            rawFields.putAll(fieldsAndWeights);
        } else if (defaultField != null && defaultField.isBlank() == false) {
            rawFields.put(defaultField, 1.0f);
        } else {
            rawFields.put("*", 1.0f);
        }

        Map<String, Float> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Float> entry : rawFields.entrySet()) {
            String fieldPattern = ConstraintBuilder.stripBoost(entry.getKey());
            float boost = entry.getValue() != null ? entry.getValue() : 1.0f;
            if ("*".equals(fieldPattern)) {
                for (String field : context.getMatchingFieldNames("*")) {
                    resolved.putIfAbsent(field, boost);
                }
                continue;
            }

            Set<String> matchingFields = context.getMatchingFieldNames(fieldPattern);
            if (matchingFields == null || matchingFields.isEmpty()) {
                resolved.putIfAbsent(fieldPattern, boost);
                continue;
            }
            for (String field : matchingFields) {
                resolved.putIfAbsent(field, boost);
            }
        }

        List<FieldSpec> specs = new ArrayList<>(resolved.size());
        for (Map.Entry<String, Float> entry : resolved.entrySet()) {
            specs.add(new FieldSpec(entry.getKey(), entry.getValue()));
        }
        return specs;
    }

    private Analyzer resolveAnalyzer(String field, boolean exact) {
        if (exact && forceQuoteAnalyzer != null) {
            return forceQuoteAnalyzer;
        }
        if (forceAnalyzer != null) {
            return forceAnalyzer;
        }

        MappedFieldType fieldType = context.getFieldType(field);
        if (fieldType != null) {
            try {
                Analyzer analyzer = fieldType.getTextSearchInfo().searchAnalyzer();
                if (analyzer != null) {
                    return analyzer;
                }
            } catch (Exception ignored) {
                // Fall back to keyword analyzer below.
            }
        }
        return KEYWORD_ANALYZER;
    }

    private List<AnalyzedToken> analyze(Analyzer analyzer, String field, String text) throws IOException {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<AnalyzedToken> tokens = new ArrayList<>();
        try (TokenStream tokenStream = analyzer.tokenStream(field, text)) {
            CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            OffsetAttribute offsetAttribute = tokenStream.addAttribute(OffsetAttribute.class);
            PositionIncrementAttribute positionIncrementAttribute = tokenStream.addAttribute(PositionIncrementAttribute.class);
            tokenStream.reset();
            int position = -1;
            while (tokenStream.incrementToken()) {
                position += positionIncrementAttribute.getPositionIncrement();
                String term = TextNormalization.normalizeAnalyzedToken(termAttribute.toString());
                if (term.isBlank()) {
                    continue;
                }
                tokens.add(new AnalyzedToken(term, offsetAttribute.startOffset(), offsetAttribute.endOffset(), position));
            }
            tokenStream.end();
        }
        return tokens;
    }

    private boolean exceedsMaxFreq(String field, String termText) {
        try {
            IndexReader reader = context.getIndexReader();
            return reader != null && reader.docFreq(new Term(field, termText)) > maxFreq;
        } catch (IOException exception) {
            return false;
        }
    }

    private String correctText(String text) {
        try {
            List<LuceneIndexSuggester.SuggestionOption> suggestions = getSuggester()
                    .suggestCorrections(suggestionFields, text, correctionConfig);
            if (suggestions.isEmpty()) {
                return text;
            }
            return suggestions.get(0).text();
        } catch (IOException exception) {
            return text;
        }
    }

    private LuceneIndexSuggester getSuggester() {
        if (suggester == null) {
            suggester = new LuceneIndexSuggester(context.getIndexReader());
        }
        return suggester;
    }

    static final class ParsedClause {
        private final String text;
        private final BooleanClause.Occur occur;
        private final boolean exact;

        ParsedClause(String text, BooleanClause.Occur occur, boolean exact) {
            this.text = text;
            this.occur = occur;
            this.exact = exact;
        }

        String text() {
            return text;
        }

        BooleanClause.Occur occur() {
            return occur;
        }

        boolean exact() {
            return exact;
        }

        ParsedClause withText(String newText) {
            return new ParsedClause(newText, occur, exact);
        }
    }

    private record FieldSpec(String field, float boost) {
    }

    private record AnalyzedToken(String term, int startOffset, int endOffset, int position) {
        boolean isUsable() {
            return term.isBlank() == false && term.startsWith("__py") == false;
        }
    }
}
