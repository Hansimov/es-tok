package org.es.tok.action;

import org.es.tok.suggest.LuceneIndexSuggester;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class TransportEsTokSuggestActionTest {

    @Test
    public void testFilterSemanticCooccurrenceOptionsKeepsOnlyRuleAlignedCandidates() {
        List<LuceneIndexSuggester.SuggestionOption> filtered = TransportEsTokSuggestAction.filterSemanticCooccurrenceOptions(
            "袁启 专访",
            List.of(
                new LuceneIndexSuggester.SuggestionOption("袁启 采访", 1, 128.0f, "rewrite"),
                new LuceneIndexSuggester.SuggestionOption("袁启 访谈", 1, 120.0f, "rewrite")),
            List.of(
                new LuceneIndexSuggester.SuggestionOption("采访", 10, 50.0f, "cooccurrence"),
                new LuceneIndexSuggester.SuggestionOption("中英", 10, 45.0f, "cooccurrence"),
                new LuceneIndexSuggester.SuggestionOption("直播", 8, 40.0f, "cooccurrence")));

        assertEquals(List.of("采访"), filtered.stream().map(LuceneIndexSuggester.SuggestionOption::text).toList());
    }

    @Test
    public void testFilterSemanticCooccurrenceOptionsRetainsOriginalAnchorExtensions() {
        List<LuceneIndexSuggester.SuggestionOption> filtered = TransportEsTokSuggestAction.filterSemanticCooccurrenceOptions(
            "开箱",
            List.of(
                new LuceneIndexSuggester.SuggestionOption("上手", 1, 82.0f, "near_synonym"),
                new LuceneIndexSuggester.SuggestionOption("体验", 1, 80.0f, "near_synonym")),
            List.of(
                new LuceneIndexSuggester.SuggestionOption("开箱视频", 30, 60.0f, "cooccurrence"),
                new LuceneIndexSuggester.SuggestionOption("视频", 20, 55.0f, "cooccurrence")));

        assertEquals(List.of("开箱视频"), filtered.stream().map(LuceneIndexSuggester.SuggestionOption::text).toList());
    }

    @Test
    public void testFilterSemanticCooccurrenceOptionsDropsNonMatchingShardNoise() {
        List<LuceneIndexSuggester.SuggestionOption> filtered = TransportEsTokSuggestAction.filterSemanticCooccurrenceOptions(
            "袁启 专访",
            List.of(
                new LuceneIndexSuggester.SuggestionOption("袁启 采访", 1, 128.0f, "rewrite"),
                new LuceneIndexSuggester.SuggestionOption("袁启 访谈", 1, 120.0f, "rewrite")),
            List.of(
                new LuceneIndexSuggester.SuggestionOption("中英", 10, 45.0f, "cooccurrence"),
                new LuceneIndexSuggester.SuggestionOption("直播", 8, 40.0f, "cooccurrence")));

        assertEquals(List.of(), filtered.stream().map(LuceneIndexSuggester.SuggestionOption::text).toList());
    }

    @Test
    public void testFilterSemanticAutoOptionsDropsNonMatchingAssociateNoise() {
        List<LuceneIndexSuggester.SuggestionOption> filtered = TransportEsTokSuggestAction.filterSemanticAutoOptions(
            "袁启 专访",
            List.of(
                new LuceneIndexSuggester.SuggestionOption("袁启 采访", 1, 128.0f, "rewrite"),
                new LuceneIndexSuggester.SuggestionOption("袁启 访谈", 1, 120.0f, "rewrite")),
            List.of(
                new LuceneIndexSuggester.SuggestionOption("元启专访", 9, 90.0f, "correction"),
                new LuceneIndexSuggester.SuggestionOption("中英", 10, 45.0f, "associate"),
                new LuceneIndexSuggester.SuggestionOption("采访", 8, 40.0f, "associate")));

        assertEquals(List.of("元启专访", "采访"), filtered.stream().map(LuceneIndexSuggester.SuggestionOption::text).toList());
    }
}