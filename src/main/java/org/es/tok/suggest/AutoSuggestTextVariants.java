package org.es.tok.suggest;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.es.tok.text.TextNormalization;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

public final class AutoSuggestTextVariants {
    private static final int MAX_LONG_TEXT_FALLBACKS = 3;

    public static boolean shouldRunLongTextFallback(
            String text,
            List<LuceneIndexSuggester.SuggestionOption> options,
            int requestedSize) {
        if (!isLongTextQuery(text)) {
            return false;
        }
        return options.size() < Math.min(2, requestedSize);
    }

    public static List<String> buildFallbackTexts(IndexService indexService, List<String> suggestFields, String text) throws IOException {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        String collapsed = TextNormalization.collapseWhitespace(text);
        String compacted = TextNormalization.compactWhitespaceAroundCjk(collapsed);
        for (String analyzedVariant : analyzedFallbackTexts(indexService, suggestFields, compacted)) {
            variants.add(analyzedVariant);
        }
        if (!compacted.equals(collapsed)) {
            variants.add(compacted);
        }
        if (compacted.codePointCount(0, compacted.length()) > 12) {
            variants.add(sliceLeadingCodePoints(compacted, 12));
        }
        if (compacted.codePointCount(0, compacted.length()) > 8) {
            variants.add(sliceLeadingCodePoints(compacted, 8));
        }
        if (compacted.codePointCount(0, compacted.length()) > 6) {
            variants.add(sliceLeadingCodePoints(compacted, 6));
        }
        if (compacted.codePointCount(0, compacted.length()) > 10) {
            variants.add(sliceTrailingCodePoints(compacted, 10));
        }
        String firstSpan = firstFallbackSpan(compacted);
        if (!firstSpan.isBlank()) {
            variants.add(firstSpan);
        }
        variants.remove(TextNormalization.collapseWhitespace(text));
        return variants.stream().limit(MAX_LONG_TEXT_FALLBACKS).toList();
    }

    private static boolean isLongTextQuery(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = TextNormalization.collapseWhitespace(text);
        return normalized.codePointCount(0, normalized.length()) >= 12 || normalized.chars().anyMatch(Character::isWhitespace);
    }

    private static List<String> analyzedFallbackTexts(
            IndexService indexService,
            List<String> suggestFields,
            String text) throws IOException {
        Analyzer analyzer = resolveFallbackAnalyzer(indexService, suggestFields);
        if (analyzer == null) {
            return List.of();
        }
        List<String> tokens = analyzeFallbackTokens(analyzer, fallbackAnalyzeField(suggestFields), text);
        if (tokens.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(tokens.get(0));
        if (tokens.size() > 1) {
            variants.add(joinFallbackTokens(tokens.subList(0, Math.min(2, tokens.size()))));
        }
        List<String> byLength = new ArrayList<>(tokens);
        byLength.sort(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo));
        variants.add(byLength.get(0));
        if (byLength.size() > 1) {
            variants.add(joinFallbackTokens(byLength.subList(0, Math.min(2, byLength.size()))));
        }
        return List.copyOf(variants);
    }

    private static Analyzer resolveFallbackAnalyzer(IndexService indexService, List<String> suggestFields) {
        if (indexService == null) {
            return Lucene.KEYWORD_ANALYZER;
        }
        String field = fallbackAnalyzeField(suggestFields);
        if (field.isBlank()) {
            return Lucene.KEYWORD_ANALYZER;
        }
        MappedFieldType fieldType = indexService.mapperService().fieldType(field);
        if (fieldType == null) {
            return Lucene.KEYWORD_ANALYZER;
        }
        return fieldType.getTextSearchInfo().searchAnalyzer();
    }

    private static String fallbackAnalyzeField(List<String> suggestFields) {
        if (suggestFields == null || suggestFields.isEmpty()) {
            return "";
        }
        return suggestFields.get(0);
    }

    private static List<String> analyzeFallbackTokens(Analyzer analyzer, String field, String text) throws IOException {
        if (analyzer == null || text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        try (TokenStream tokenStream = analyzer.tokenStream(field, text)) {
            CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                String token = TextNormalization.normalizeLower(termAttribute.toString());
                if (!token.isBlank()) {
                    tokens.add(token);
                }
            }
            tokenStream.end();
        }
        return List.copyOf(tokens);
    }

    private static String joinFallbackTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return "";
        }
        boolean hasNonAscii = tokens.stream().flatMapToInt(String::chars).anyMatch(ch -> ch >= 128);
        return hasNonAscii ? String.join("", tokens) : String.join(" ", tokens);
    }

    private static String firstFallbackSpan(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] parts = TextNormalization.collapseWhitespace(text).split(" ");
        if (parts.length == 0) {
            return "";
        }
        if (parts[0].codePointCount(0, parts[0].length()) <= 10) {
            return parts[0];
        }
        return sliceLeadingCodePoints(parts[0], 6);
    }

    private static String sliceLeadingCodePoints(String text, int count) {
        int[] codePoints = text.codePoints().limit(count).toArray();
        return codePoints.length == 0 ? "" : new String(codePoints, 0, codePoints.length);
    }

    private static String sliceTrailingCodePoints(String text, int count) {
        int[] codePoints = text.codePoints().toArray();
        int start = Math.max(0, codePoints.length - count);
        return codePoints.length == 0 ? "" : new String(codePoints, start, codePoints.length - start);
    }

    private AutoSuggestTextVariants() {
    }
}