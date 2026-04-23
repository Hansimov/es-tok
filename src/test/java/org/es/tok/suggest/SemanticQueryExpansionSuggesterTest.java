package org.es.tok.suggest;

import org.es.tok.suggest.LuceneIndexSuggester.SuggestionOption;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SemanticQueryExpansionSuggesterTest {

    @Test
    public void testWhitespaceQueryExpandsInterviewVariants() {
        SemanticQueryExpansionSuggester suggester = new SemanticQueryExpansionSuggester();

        List<SuggestionOption> options = suggester.suggest("袁启 专访", 8);
        List<String> texts = options.stream().map(SuggestionOption::text).toList();

        assertTrue(texts.toString(), texts.contains("袁启 采访"));
        assertTrue(texts.toString(), texts.contains("袁启 访谈"));
    }

    @Test
    public void testContinuousChineseQueryExpandsEmbeddedInterviewTerm() {
        SemanticQueryExpansionSuggester suggester = new SemanticQueryExpansionSuggester();

        List<SuggestionOption> options = suggester.suggest("袁启专访", 6);
        List<String> texts = options.stream().map(SuggestionOption::text).toList();

        assertTrue(texts.toString(), texts.contains("袁启采访"));
    }

    @Test
    public void testNearSynonymCandidatesExposeNearSynonymType() {
        SemanticQueryExpansionSuggester suggester = new SemanticQueryExpansionSuggester();

        List<SuggestionOption> options = suggester.suggest("开箱", 6);

        assertEquals("near_synonym", options.get(0).type());
        assertTrue(options.stream().map(SuggestionOption::text).toList().toString(),
            options.stream().anyMatch(option -> option.text().equals("上手") || option.text().equals("体验")));
    }
}