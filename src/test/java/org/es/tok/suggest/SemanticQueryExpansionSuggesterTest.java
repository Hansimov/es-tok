package org.es.tok.suggest;

import org.es.tok.suggest.LuceneIndexSuggester.SuggestionOption;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test
    public void testDocCooccurrenceDoesNotRewriteContainedFragments() {
        SemanticExpansionStore store = new MapBackedSemanticExpansionStore(Map.of(
            "pv", List.of(new SemanticExpansionStore.SemanticExpansionRule("健康指南", "doc_cooccurrence", 0.9f)),
            "专访", List.of(new SemanticExpansionStore.SemanticExpansionRule("采访", "rewrite", 1.0f))
        ));
        SemanticQueryExpansionSuggester suggester = new SemanticQueryExpansionSuggester(store);

        List<SuggestionOption> options = suggester.suggest("版本pv 专访", 8);
        List<String> texts = options.stream().map(SuggestionOption::text).toList();

        assertTrue(texts.toString(), texts.contains("版本pv 采访"));
        assertFalse(texts.toString(), texts.contains("版本健康指南 专访"));
    }

    private record MapBackedSemanticExpansionStore(
        Map<String, List<SemanticExpansionStore.SemanticExpansionRule>> rules
    ) implements SemanticExpansionStore {
        @Override
        public List<SemanticExpansionRule> expansions(String surface) {
            return rules.getOrDefault(SemanticExpansionStore.normalizeSurface(surface), List.of());
        }

        @Override
        public List<String> matchingTerms(String surface) {
            String normalized = SemanticExpansionStore.normalizeSurface(surface);
            return rules.keySet().stream().filter(normalized::contains).toList();
        }
    }
}
