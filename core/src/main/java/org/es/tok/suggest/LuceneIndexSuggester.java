package org.es.tok.suggest;

import org.es.tok.text.TextNormalization;
import org.es.tok.text.TopicQualityHeuristics;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.spell.DirectSpellChecker;
import org.apache.lucene.search.spell.SuggestMode;
import org.apache.lucene.search.spell.SuggestWord;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Index-level spell-correction and completion helper backed by Lucene terms.
 * <p>
 * This class only touches the term dictionary and doc frequencies. It never
 * scans documents, which keeps it viable for large indexes as long as the
 * number of candidate expansions is bounded.
 */
public class LuceneIndexSuggester {

    private static final List<String> COMPLETION_WARMUP_ANCHORS = buildCompletionWarmupAnchors();
    private static final int EXACT_FULL_PINYIN_PREFIX_LENGTH = 8;
    private static final int EXACT_INITIALS_PINYIN_PREFIX_LENGTH = 8;
    private static final int EXACT_FULL_PINYIN_PREFIX_MIN_LENGTH = 4;
    private static final int EXACT_INITIALS_PINYIN_PREFIX_MIN_LENGTH = 3;
    private static final int EXACT_PREFIX_COARSE_FALLBACK_THRESHOLD = 24;
    private static final int COARSE_PINYIN_BUCKET_LIMIT = 2048;

    private final IndexReader reader;

    public LuceneIndexSuggester(IndexReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public void prewarmPinyinIndices(Collection<String> fields) throws IOException {
        for (String field : normalizeFields(fields)) {
            pinyinFieldIndex(field);
        }
    }

    public void prewarmCompletionIndices(Collection<String> fields) throws IOException {
        for (String field : normalizeFields(fields)) {
            prewarmCompletionField(field);
        }
    }

    public Correction suggestCorrection(Collection<String> fields, String token, CorrectionConfig config)
            throws IOException {
        return suggestCorrectionCandidates(fields, token, config).stream()
                .findFirst()
                .orElseGet(() -> Correction.unchanged(token, 0));
    }

    public List<Correction> suggestCorrectionCandidates(
            Collection<String> fields,
            String token,
            CorrectionConfig config) throws IOException {
        if (token == null || token.isBlank()) {
            return List.of(Correction.unchanged(token, 0));
        }

        CorrectionConfig effectiveConfig = config != null ? config : CorrectionConfig.defaults();
        List<String> normalizedFields = normalizeFields(fields);
        if (normalizedFields.isEmpty() || token.length() < effectiveConfig.minTokenLength()) {
            return List.of(Correction.unchanged(token, 0));
        }

        int originalDocFreq = maxDocFreq(normalizedFields, token);
        if (originalDocFreq > effectiveConfig.rareTermDocFreq()) {
            return List.of(Correction.unchanged(token, originalDocFreq));
        }

        boolean skipDirectSpellChecker = effectiveConfig.usePinyin() && PinyinSupport.containsChinese(token);
        DirectSpellChecker checker = skipDirectSpellChecker ? null : createSpellChecker(effectiveConfig);
        Map<String, CorrectionCandidateAccumulator> candidates = new HashMap<>();

        for (int fieldIndex = 0; fieldIndex < normalizedFields.size(); fieldIndex++) {
            String field = normalizedFields.get(fieldIndex);
            float fieldWeight = orderedFieldWeight(fieldIndex, normalizedFields.size());
            if (checker != null) {
                SuggestWord[] words = checker.suggestSimilar(
                        new Term(field, token),
                        effectiveConfig.maxSuggestions(),
                        reader,
                        SuggestMode.SUGGEST_MORE_POPULAR);

                for (SuggestWord word : words) {
                    if (word == null || word.string == null || token.equals(word.string)) {
                        continue;
                    }
                    if (!isAcceptableCorrectionCandidate(token, word.string)) {
                        continue;
                    }
                    if (PinyinSupport.containsChinese(token) && !PinyinSupport.matchesChineseAnchor(token, word.string)) {
                        continue;
                    }
                    String normalizedSuggestion = normalizeSuggestionSurface(word.string);
                    CorrectionCandidateAccumulator accumulator = candidates.computeIfAbsent(
                            normalizedSuggestion,
                            ignored -> new CorrectionCandidateAccumulator(normalizedSuggestion));
                    accumulator.add(field, word, docFreq(field, word.string));
                }
            }

            collectShortCjkCorrectionCandidates(field, token, effectiveConfig, candidates);
            if (effectiveConfig.usePinyin()) {
                collectPinyinCorrectionCandidates(field, token, effectiveConfig, candidates, fieldWeight);
            }
        }

        List<Correction> corrections = candidates.values().stream()
                .filter(candidate -> candidate.maxDocFreq >= effectiveConfig.minCandidateDocFreq())
                .filter(candidate -> candidate.maxDocFreq > originalDocFreq)
                .sorted(CorrectionCandidateAccumulator.ORDER)
                .map(candidate -> candidate.toCorrection(token, originalDocFreq))
            .limit(effectiveConfig.maxSuggestions())
            .toList();
        if (corrections.isEmpty()) {
            return List.of(Correction.unchanged(token, originalDocFreq));
        }
        return corrections;
    }

    public List<SuggestionOption> suggestCorrections(
            Collection<String> fields,
            String text,
            CorrectionConfig config) throws IOException {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String[] rawTokens = text.trim().split("\\s+");
        if (rawTokens.length == 0) {
            return List.of();
        }

        CorrectionConfig effectiveConfig = config != null ? config : CorrectionConfig.defaults();
        List<List<Correction>> tokenCandidates = new ArrayList<>(rawTokens.length);
        boolean hasChanges = false;
        for (String rawToken : rawTokens) {
            List<Correction> candidates = suggestCorrectionCandidates(fields, rawToken, effectiveConfig);
            if (candidates.stream().anyMatch(Correction::changed)) {
                hasChanges = true;
            }
            tokenCandidates.add(candidates);
        }

        if (!hasChanges) {
            return List.of();
        }

        int beamWidth = Math.max(effectiveConfig.maxSuggestions(), Math.min(64, effectiveConfig.maxSuggestions() * 4));
        List<PhraseCandidate> beam = List.of(PhraseCandidate.empty());
        for (List<Correction> candidates : tokenCandidates) {
            List<PhraseCandidate> expanded = new ArrayList<>(beam.size() * candidates.size());
            for (PhraseCandidate prefix : beam) {
                for (Correction candidate : candidates) {
                    expanded.add(prefix.append(candidate));
                }
            }
            beam = expanded.stream()
                    .sorted(PhraseCandidate.ORDER)
                    .limit(beamWidth)
                    .toList();
        }

        Map<String, PhraseCandidate> deduplicated = new HashMap<>();
        for (PhraseCandidate candidate : beam.stream().sorted(PhraseCandidate.ORDER).toList()) {
            if (!candidate.changed()) {
                continue;
            }
            PhraseCandidate existing = deduplicated.get(candidate.text());
            if (existing == null || PhraseCandidate.ORDER.compare(candidate, existing) < 0) {
                deduplicated.put(candidate.text(), candidate);
            }
        }

        return deduplicated.values().stream()
                .sorted(PhraseCandidate.ORDER)
                .limit(effectiveConfig.maxSuggestions())
                .map(PhraseCandidate::toSuggestionOption)
                .toList();
    }

    public List<CompletionCandidate> suggestPrefixCompletions(
            Collection<String> fields,
            String prefix,
            CompletionConfig config) throws IOException {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }

        CompletionConfig effectiveConfig = config != null ? config : CompletionConfig.defaults();
        if (prefix.length() < effectiveConfig.minPrefixLength()) {
            return List.of();
        }

        Map<String, CompletionAccumulator> candidates = new HashMap<>();
        List<String> normalizedFields = normalizeFields(fields);
        for (int fieldIndex = 0; fieldIndex < normalizedFields.size(); fieldIndex++) {
            String field = normalizedFields.get(fieldIndex);
            float fieldWeight = orderedFieldWeight(fieldIndex, normalizedFields.size());
            collectPrefixMatches(field, prefix, effectiveConfig, candidates);
            if (effectiveConfig.usePinyin()) {
                collectPinyinPrefixMatches(field, prefix, effectiveConfig, candidates, fieldWeight);
                collectPinyinCorrectionStylePrefixMatches(field, prefix, effectiveConfig, candidates, fieldWeight);
            }
        }
        return finalizeCompletions(candidates, effectiveConfig.size());
    }

    public List<CompletionCandidate> suggestNextTokenCompletions(
            Collection<String> fields,
            String token,
            CompletionConfig config) throws IOException {
        if (token == null || token.isBlank()) {
            return List.of();
        }

        CompletionConfig effectiveConfig = config != null ? config : CompletionConfig.defaults();
        if (token.length() < effectiveConfig.minPrefixLength()) {
            return List.of();
        }

        Map<String, CompletionAccumulator> candidates = new HashMap<>();
        for (String field : normalizeFields(fields)) {
            collectSpacedBigramMatches(field, token + " ", effectiveConfig, candidates);
            if (effectiveConfig.allowCompactBigrams() && shouldCollectCompactBigramMatches(token)) {
                collectCompactBigramMatches(field, token, effectiveConfig, candidates);
            }
        }
        return finalizeCompletions(candidates, effectiveConfig.size());
    }

    public List<SuggestionOption> suggestAuto(
            Collection<String> fields,
            String text,
            CompletionConfig config,
            CorrectionConfig correctionConfig) throws IOException {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        CompletionConfig effectiveCompletionConfig = config != null ? config : CompletionConfig.defaults();
        CorrectionConfig effectiveCorrectionConfig = correctionConfig != null ? correctionConfig : CorrectionConfig.defaults();
        Map<String, AutoSuggestionAccumulator> merged = new HashMap<>();

        mergeAutoCompletionCandidates(
                merged,
                suggestPrefixCompletions(fields, text, effectiveCompletionConfig),
                "prefix",
                1.0f);

        if (shouldIncludeNextTokenInAuto(text)) {
            mergeAutoCompletionCandidates(
                    merged,
                    suggestNextTokenCompletions(fields, text, effectiveCompletionConfig),
                    "next_token",
                    autoNextTokenWeight(text));
        }

        mergeAutoCorrectionCandidates(
                merged,
                suggestCorrections(fields, text, effectiveCorrectionConfig),
                text,
                0.92f);

        return merged.values().stream()
                .sorted(AutoSuggestionAccumulator.ORDER)
                .limit(effectiveCompletionConfig.size())
                .map(AutoSuggestionAccumulator::toSuggestionOption)
                .toList();
    }

    private DirectSpellChecker createSpellChecker(CorrectionConfig config) {
        DirectSpellChecker checker = new DirectSpellChecker();
        checker.setAccuracy(config.accuracy());
        checker.setMaxEdits(config.maxEdits());
        checker.setMinPrefix(config.prefixLength());
        checker.setMinQueryLength(config.minTokenLength());
        return checker;
    }

    private void collectPrefixMatches(
            String field,
            String prefix,
            CompletionConfig config,
            Map<String, CompletionAccumulator> candidates) throws IOException {
        if (collectLiteralPrefixMatches(field, prefix, config, candidates)) {
            return;
        }

        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return;
        }

        TermsEnum termsEnum = terms.iterator();
        if (termsEnum.seekCeil(new BytesRef(prefix)) == TermsEnum.SeekStatus.END) {
            return;
        }

        int visited = 0;
        BytesRef current = termsEnum.term();
        String normalizedPrefix = normalizeSuggestionSurface(prefix);
        while (current != null && visited < config.scanLimit()) {
            String text = current.utf8ToString();
            if (!text.startsWith(prefix)) {
                break;
            }
            visited++;
            String normalizedText = normalizeSuggestionSurface(text);
            if (normalizedText.length() >= config.minCandidateLength()) {
                addCompletionCandidate(
                        candidates,
                        normalizedText,
                        termsEnum.docFreq(),
                        termsEnum.docFreq(),
                        CompletionType.PREFIX,
                        prefixScore(normalizedText, termsEnum.docFreq(), normalizedPrefix));
            }
            current = termsEnum.next();
        }
    }

    private void collectSpacedBigramMatches(
            String field,
            String prefix,
            CompletionConfig config,
            Map<String, CompletionAccumulator> candidates) throws IOException {
        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return;
        }

        TermsEnum termsEnum = terms.iterator();
        if (termsEnum.seekCeil(new BytesRef(prefix)) == TermsEnum.SeekStatus.END) {
            return;
        }

        int visited = 0;
        BytesRef current = termsEnum.term();
        while (current != null && visited < config.scanLimit()) {
            String text = current.utf8ToString();
            if (!text.startsWith(prefix)) {
                break;
            }
            visited++;
            String tail = normalizeSuggestionSurface(text.substring(prefix.length()));
            if (isAcceptableNextTokenTail(tail, config.minCandidateLength())) {
                int tailDocFreq = docFreq(field, tail);
                addCompletionCandidate(
                        candidates,
                        tail,
                        termsEnum.docFreq(),
                        tailDocFreq,
                        CompletionType.NEXT_TOKEN,
                        nextTokenScore(tail, termsEnum.docFreq(), tailDocFreq));
            }
            current = termsEnum.next();
        }
    }

    private void collectCompactBigramMatches(
            String field,
            String token,
            CompletionConfig config,
            Map<String, CompletionAccumulator> candidates) throws IOException {
        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return;
        }

        TermsEnum termsEnum = terms.iterator();
        if (termsEnum.seekCeil(new BytesRef(token)) == TermsEnum.SeekStatus.END) {
            return;
        }

        int visited = 0;
        BytesRef current = termsEnum.term();
        while (current != null && visited < config.scanLimit()) {
            String text = current.utf8ToString();
            if (!text.startsWith(token)) {
                break;
            }
            visited++;
            if (containsWhitespace(text) || text.length() <= token.length()) {
                current = termsEnum.next();
                continue;
            }

            String tail = normalizeSuggestionSurface(text.substring(token.length()));
            int tailDocFreq = docFreq(field, tail);
            if (tailDocFreq > 0 && isAcceptableNextTokenTail(tail, config.minCandidateLength())) {
                addCompletionCandidate(
                        candidates,
                        tail,
                        termsEnum.docFreq(),
                        tailDocFreq,
                        CompletionType.NEXT_TOKEN,
                        nextTokenScore(tail, termsEnum.docFreq(), tailDocFreq));
            }
            current = termsEnum.next();
        }
    }

    private void addCompletionCandidate(
            Map<String, CompletionAccumulator> candidates,
            String text,
            int docFreq,
            int tailDocFreq,
            CompletionType type,
            float score) {
        String normalizedText = normalizeSuggestionSurface(text);
        CompletionAccumulator accumulator = candidates.computeIfAbsent(normalizedText, CompletionAccumulator::new);
        accumulator.add(docFreq, tailDocFreq, type, score);
    }

    private static float prefixScore(String text, int docFreq, String prefix) {
        float score = (float) (Math.log1p(docFreq) * 10.0d);
        if (text == null || text.isBlank()) {
            return score;
        }

        String normalizedPrefix = normalizeSuggestionSurface(prefix);
        int textLength = text.codePointCount(0, text.length());
        int prefixLength = normalizedPrefix.codePointCount(0, normalizedPrefix.length());
        int growth = Math.max(0, textLength - prefixLength);
        boolean pureChinesePrefix = PinyinSupport.isPureChineseQuery(normalizedPrefix);
        boolean literalPrefix = !normalizedPrefix.isBlank() && text.startsWith(normalizedPrefix);
        boolean asciiLiteralPrefix = PinyinSupport.isAsciiAlphaNumericQuery(normalizedPrefix);
        boolean digitHeavyPrefix = normalizedPrefix.chars().anyMatch(Character::isDigit);

        float shapeFactor = 1.0f;
        if (text.equals(normalizedPrefix)) {
            if (asciiLiteralPrefix) {
                if (prefixLength <= 2) {
                    shapeFactor *= 0.75f;
                } else if (prefixLength <= 4) {
                    shapeFactor *= 3.6f;
                } else if (prefixLength <= 8) {
                    shapeFactor *= 2.6f;
                } else {
                    shapeFactor *= 1.85f;
                }
                if (digitHeavyPrefix) {
                    shapeFactor *= 1.22f;
                }
            } else if (prefixLength <= 2) {
                shapeFactor *= 0.05f;
            } else if (prefixLength <= 4) {
                shapeFactor *= 0.18f;
            } else {
                shapeFactor *= 0.45f;
            }
        } else if (growth <= 2) {
            shapeFactor *= 1.22f;
        } else if (growth <= 4) {
            shapeFactor *= 1.12f;
        } else if (growth >= 8) {
            shapeFactor *= 0.84f;
        }

        if (pureChinesePrefix && literalPrefix) {
            if (growth <= 1) {
                shapeFactor *= 12.0f;
            } else if (growth <= 3) {
                shapeFactor *= 9.0f;
            } else {
                shapeFactor *= 6.5f;
            }
        }

        if (textLength == 1) {
            shapeFactor *= 0.55f;
        }
        if (containsWhitespace(text)) {
            shapeFactor *= 0.82f;
        }
        if (text.indexOf(' ') > 0 && text.chars().allMatch(ch -> ch < 128 || Character.isWhitespace(ch))) {
            shapeFactor *= 1.08f;
        }
        if (hasLeadingOrTrailingFunctionChar(text)) {
            shapeFactor *= 0.72f;
        }

        return score * shapeFactor;
    }

    private static float nextTokenScore(String text, int docFreq, int tailDocFreq) {
        float score = docFreq;
        if (text == null || text.isBlank()) {
            return score;
        }

        int codePointLength = text.codePointCount(0, text.length());
        int firstCodePoint = text.codePointAt(0);
        boolean asciiOnly = text.chars().allMatch(ch -> ch < 128);
        boolean hasWhitespace = containsWhitespace(text);
        boolean hasDigit = text.chars().anyMatch(Character::isDigit);
        boolean hasAscii = text.chars().anyMatch(ch -> ch < 128 && Character.isLetterOrDigit(ch));

        if (tailDocFreq > 0) {
            score = (float) ((docFreq / Math.sqrt(tailDocFreq)) * Math.log1p(docFreq));
        }

        float shapeFactor = 1.0f;
        if (codePointLength == 1) {
            shapeFactor *= isCommonFunctionCodePoint(firstCodePoint) ? 0.08f : 0.72f;
        } else if (codePointLength == 2) {
            shapeFactor *= 1.24f;
        } else if (codePointLength <= 4) {
            shapeFactor *= 1.15f;
        } else if (codePointLength <= 6) {
            shapeFactor *= 0.84f;
        } else {
            shapeFactor *= 0.62f;
        }

        if (asciiOnly && codePointLength >= 4) {
            shapeFactor *= 1.05f;
        }
        if (!asciiOnly && hasAscii && hasDigit) {
            shapeFactor *= 0.72f;
        }

        if (hasWhitespace) {
            shapeFactor *= 0.4f;
        }
        if (hasDigit) {
            shapeFactor *= 0.78f;
        }
        if (isFunctionWordHeavy(text)) {
            shapeFactor *= 0.2f;
        } else if (hasLeadingOrTrailingFunctionChar(text)) {
            shapeFactor *= 0.36f;
        }

        return score * shapeFactor;
    }

    private void collectShortCjkCorrectionCandidates(
            String field,
            String token,
            CorrectionConfig config,
            Map<String, CorrectionCandidateAccumulator> candidates) throws IOException {
        if (!isShortCjkCorrectionToken(token)) {
            return;
        }

        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return;
        }

        int tokenCodePointLength = token.codePointCount(0, token.length());
        for (String prefix : shortCjkPrefixes(token)) {
            TermsEnum termsEnum = terms.iterator();
            if (termsEnum.seekCeil(new BytesRef(prefix)) == TermsEnum.SeekStatus.END) {
                continue;
            }

            int visited = 0;
            BytesRef current = termsEnum.term();
            while (current != null && visited < 128) {
                String candidate = normalizeSuggestionSurface(current.utf8ToString());
                if (!candidate.startsWith(prefix)) {
                    break;
                }

                visited++;
                current = termsEnum.next();

                if (!isAcceptableCorrectionCandidate(token, candidate) || token.equals(candidate)) {
                    continue;
                }

                int candidateCodePointLength = candidate.codePointCount(0, candidate.length());
                if (candidateCodePointLength < 2 || candidateCodePointLength > 4) {
                    continue;
                }
                if (Math.abs(candidateCodePointLength - tokenCodePointLength) > 1) {
                    continue;
                }

                int distance = boundedCodePointEditDistance(token, candidate, Math.min(2, config.maxEdits()));
                if (distance < 0) {
                    continue;
                }

                int candidateDocFreq = docFreq(field, candidate);
                if (candidateDocFreq < config.minCandidateDocFreq()) {
                    continue;
                }
                if (PinyinSupport.containsChinese(token) && !PinyinSupport.matchesChineseAnchor(token, candidate)) {
                    continue;
                }

                float score = shortCjkCorrectionScore(token, candidate, candidateDocFreq, distance);
                candidates.computeIfAbsent(candidate, CorrectionCandidateAccumulator::new)
                        .add(field, candidate, score, candidateDocFreq);
            }
        }
    }

    private void collectPinyinCorrectionCandidates(
            String field,
            String token,
            CorrectionConfig config,
            Map<String, CorrectionCandidateAccumulator> candidates,
            float fieldWeight) throws IOException {
        for (PinyinIndexedTerm indexedTerm : pinyinFieldIndex(field).candidates(token)) {
            if (token.equals(indexedTerm.text())) {
                continue;
            }
            float pinyinScore = PinyinSupport.correctionMatchScore(token, indexedTerm.text());
            if (pinyinScore <= 0.0f) {
                continue;
            }
            float score = fieldWeight * correctionScore(indexedTerm.text(), (pinyinScore * 3.0f) + 0.6f, indexedTerm.docFreq());
            candidates.computeIfAbsent(indexedTerm.text(), CorrectionCandidateAccumulator::new)
                    .add(field, indexedTerm.text(), score, indexedTerm.docFreq());
        }
    }

    private void collectPinyinCorrectionStylePrefixMatches(
            String field,
            String prefix,
            CompletionConfig config,
            Map<String, CompletionAccumulator> candidates,
            float fieldWeight) throws IOException {
        if (PinyinSupport.isPureChineseQuery(prefix)) {
            return;
        }
        for (PinyinIndexedTerm indexedTerm : pinyinFieldIndex(field).candidates(prefix)) {
            float pinyinScore = PinyinSupport.correctionMatchScore(prefix, indexedTerm.text());
            if (pinyinScore <= 0.0f) {
                continue;
            }
            float score = fieldWeight * (float) ((Math.log1p(indexedTerm.docFreq()) * 8.0d) * pinyinScore);
            addCompletionCandidate(
                    candidates,
                    indexedTerm.text(),
                    indexedTerm.docFreq(),
                    indexedTerm.docFreq(),
                    CompletionType.PREFIX,
                    score);
        }
    }

    private void collectPinyinPrefixMatches(
            String field,
            String prefix,
            CompletionConfig config,
            Map<String, CompletionAccumulator> candidates,
            float fieldWeight) throws IOException {
        for (PinyinIndexedTerm indexedTerm : pinyinFieldIndex(field).candidates(prefix)) {
            float pinyinScore = PinyinSupport.prefixMatchScore(prefix, indexedTerm.text(), indexedTerm.pinyinKey());
            if (pinyinScore <= 0.0f) {
                continue;
            }
            float score = fieldWeight * (float) ((Math.log1p(indexedTerm.docFreq()) * 10.0d) * pinyinScore);
            addCompletionCandidate(
                    candidates,
                    indexedTerm.text(),
                    indexedTerm.docFreq(),
                    indexedTerm.docFreq(),
                    CompletionType.PREFIX,
                    score);
        }
    }

    private List<CompletionCandidate> finalizeCompletions(
            Map<String, CompletionAccumulator> candidates,
            int limit) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        return candidates.values().stream()
                .sorted(CompletionAccumulator.ORDER)
                .limit(limit)
                .map(CompletionAccumulator::toCandidate)
                .toList();
    }

    private void mergeAutoCompletionCandidates(
            Map<String, AutoSuggestionAccumulator> merged,
            List<CompletionCandidate> candidates,
            String sourceType,
            float branchWeight) {
        if (candidates.isEmpty()) {
            return;
        }

        float topScore = Math.max(1.0f, candidates.get(0).score());
        for (int index = 0; index < candidates.size(); index++) {
            CompletionCandidate candidate = candidates.get(index);
            float fusedScore = branchWeight * autoBranchScore(candidate.score(), topScore, candidate.docFreq(), index);
            merged.computeIfAbsent(candidate.text(), AutoSuggestionAccumulator::new)
                    .add(candidate.text(), candidate.docFreq(), fusedScore, sourceType);
        }
    }

    private void mergeAutoCorrectionCandidates(
            Map<String, AutoSuggestionAccumulator> merged,
            List<SuggestionOption> candidates,
            String originalText,
            float branchWeight) {
        if (candidates.isEmpty()) {
            return;
        }

        String normalizedOriginal = normalizeSuggestionSurface(originalText);
        float topScore = Math.max(1.0f, candidates.get(0).score());
        for (int index = 0; index < candidates.size(); index++) {
            SuggestionOption candidate = candidates.get(index);
            float affinity = 1.0f + (Math.min(2, sharedLeadingCodePointCount(normalizedOriginal, candidate.text())) * 0.08f);
            float fusedScore = branchWeight * affinity * autoBranchScore(candidate.score(), topScore, candidate.docFreq(), index);
            merged.computeIfAbsent(candidate.text(), AutoSuggestionAccumulator::new)
                    .add(candidate.text(), candidate.docFreq(), fusedScore, candidate.type());
        }
    }

    private static float autoBranchScore(float score, float topScore, int docFreq, int rank) {
        float normalizedScore = Math.max(0.0f, score) / Math.max(1.0f, topScore);
        float rankBonus = Math.max(0.0f, 1.0f - (rank * 0.08f));
        return (normalizedScore * 100.0f) + (rankBonus * 8.0f) + (float) (Math.log1p(Math.max(0, docFreq)) * 3.0d);
    }

    private List<String> normalizeFields(Collection<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String field : fields) {
            if (field == null || field.isBlank() || "*".equals(field)) {
                continue;
            }
            normalized.add(field);
        }
        return List.copyOf(normalized);
    }

    private static float orderedFieldWeight(int fieldIndex, int totalFields) {
        if (totalFields <= 1) {
            return 1.0f;
        }
        return 1.0f + (((totalFields - 1) - fieldIndex) * 1.2f);
    }

    private PinyinFieldIndex pinyinFieldIndex(String field) throws IOException {
        Object cacheKey = pinyinCacheKey(reader);
        return PinyinIndexCache.getOrBuild(cacheKey, field, () -> buildPinyinFieldIndex(field, hasPrecomputedPinyinTerms(field)));
    }

    private LiteralFieldIndex literalFieldIndex(String field) throws IOException {
        Object cacheKey = pinyinCacheKey(reader);
        return LiteralPrefixIndexCache.getOrBuild(cacheKey, field, () -> buildLiteralFieldIndex(field));
    }

    private void prewarmCompletionField(String field) throws IOException {
        literalFieldIndex(field);

        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return;
        }

        TermsEnum termsEnum = terms.iterator();
        LinkedHashSet<String> secondaryPrefixes = new LinkedHashSet<>();
        for (String prefix : COMPLETION_WARMUP_ANCHORS) {
            warmPrefixAnchor(termsEnum, prefix, 48, secondaryPrefixes);
        }
        for (String prefix : secondaryPrefixes) {
            warmPrefixAnchor(termsEnum, prefix, 12, null);
        }
    }

    private static void warmPrefixAnchor(
            TermsEnum termsEnum,
            String prefix,
            int scanLimit,
            LinkedHashSet<String> secondaryPrefixes) throws IOException {
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        if (termsEnum.seekCeil(new BytesRef(prefix)) == TermsEnum.SeekStatus.END) {
            return;
        }

        int visited = 0;
        BytesRef current = termsEnum.term();
        while (current != null && visited < scanLimit) {
            String text = current.utf8ToString();
            if (!text.startsWith(prefix)) {
                break;
            }
            termsEnum.docFreq();
            if (secondaryPrefixes != null) {
                collectSecondaryWarmPrefixes(text, secondaryPrefixes);
            }
            visited++;
            current = termsEnum.next();
        }
    }

    private static void collectSecondaryWarmPrefixes(String text, LinkedHashSet<String> secondaryPrefixes) {
        if (text == null || text.isBlank() || secondaryPrefixes.size() >= 256) {
            return;
        }

        String normalized = normalizeSuggestionSurface(text);
        if (normalized.isBlank()) {
            return;
        }

        StringBuilder builder = new StringBuilder(Math.min(8, normalized.length()));
        int codePointCount = 0;
        for (int index = 0; index < normalized.length() && codePointCount < 5; ) {
            int codePoint = normalized.codePointAt(index);
            if (!isWarmableCompletionCodePoint(codePoint)) {
                break;
            }
            builder.appendCodePoint(codePoint);
            codePointCount++;
            if (codePointCount >= 2) {
                secondaryPrefixes.add(builder.toString());
                if (secondaryPrefixes.size() >= 256) {
                    return;
                }
            }
            index += Character.charCount(codePoint);
        }
    }

    private static boolean isWarmableCompletionCodePoint(int codePoint) {
        return (codePoint < 128 && Character.isLetterOrDigit(codePoint))
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static List<String> buildCompletionWarmupAnchors() {
        List<String> anchors = new ArrayList<>(37);
        for (char ch = 'a'; ch <= 'z'; ch++) {
            anchors.add(String.valueOf(ch));
        }
        for (char ch = '0'; ch <= '9'; ch++) {
            anchors.add(String.valueOf(ch));
        }
        anchors.add("\u4e00");
        return List.copyOf(anchors);
    }

    private boolean hasPrecomputedPinyinTerms(String field) throws IOException {
        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return false;
        }

        TermsEnum termsEnum = terms.iterator();
        if (termsEnum.seekCeil(new BytesRef(PinyinSupport.PRECOMPUTED_FULL_PREFIX)) == TermsEnum.SeekStatus.END) {
            return false;
        }

        BytesRef term = termsEnum.term();
        return term != null && term.utf8ToString().startsWith(PinyinSupport.PRECOMPUTED_FULL_PREFIX);
    }

    private static Object pinyinCacheKey(IndexReader reader) {
        List<Object> segmentKeys = new ArrayList<>();
        reader.leaves().forEach(leaf -> {
            IndexReader.CacheHelper coreCacheHelper = leaf.reader().getCoreCacheHelper();
            if (coreCacheHelper != null) {
                segmentKeys.add(coreCacheHelper.getKey());
            }
        });
        if (segmentKeys.isEmpty() == false) {
            return List.copyOf(segmentKeys);
        }

        IndexReader.CacheHelper cacheHelper = reader.getReaderCacheHelper();
        return cacheHelper != null ? cacheHelper.getKey() : reader;
    }

    private boolean collectLiteralPrefixMatches(
            String field,
            String prefix,
            CompletionConfig config,
            Map<String, CompletionAccumulator> candidates) throws IOException {
        String normalizedPrefix = normalizeSuggestionSurface(prefix);
        String literalKey = literalPrefixKey(normalizedPrefix);
        if (literalKey.isBlank()) {
            return false;
        }

        boolean found = false;
        for (LiteralIndexedTerm indexedTerm : literalFieldIndex(field).candidates(literalKey)) {
            if (!indexedTerm.literalKey().startsWith(literalKey)) {
                continue;
            }
            if (!indexedTerm.text().startsWith(normalizedPrefix)) {
                continue;
            }
            if (indexedTerm.text().length() < config.minCandidateLength()) {
                continue;
            }
            found = true;
            addCompletionCandidate(
                    candidates,
                    indexedTerm.text(),
                    indexedTerm.docFreq(),
                    indexedTerm.docFreq(),
                    CompletionType.PREFIX,
                    prefixScore(indexedTerm.text(), indexedTerm.docFreq(), normalizedPrefix));
        }
        return found;
    }

    private LiteralFieldIndex buildLiteralFieldIndex(String field) throws IOException {
        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return LiteralFieldIndex.EMPTY;
        }

        final int maxLiteralTerms = 250_000;
        PriorityQueue<LiteralIndexedTerm> retained = new PriorityQueue<>(Comparator
                .comparingInt(LiteralIndexedTerm::docFreq)
                .thenComparing(LiteralIndexedTerm::text));
        TermsEnum termsEnum = terms.iterator();
        BytesRef current;
        while ((current = termsEnum.next()) != null) {
            String normalized = normalizeSuggestionSurface(current.utf8ToString());
            String literalKey = literalPrefixKey(normalized);
            if (literalKey.isBlank()) {
                continue;
            }

            LiteralIndexedTerm indexedTerm = new LiteralIndexedTerm(normalized, termsEnum.docFreq(), literalKey);
            if (retained.size() < maxLiteralTerms) {
                retained.add(indexedTerm);
                continue;
            }
            LiteralIndexedTerm smallest = retained.peek();
            if (smallest != null && compareLiteralTerms(indexedTerm, smallest) > 0) {
                retained.poll();
                retained.add(indexedTerm);
            }
        }

        List<LiteralIndexedTerm> termsList = retained.stream()
                .sorted(Comparator.comparingInt(LiteralIndexedTerm::docFreq).reversed().thenComparing(LiteralIndexedTerm::text))
                .toList();
        return new LiteralFieldIndex(termsList, buildLiteralPrefixBuckets(termsList));
    }

    private static String literalPrefixKey(String text) {
        if (!PinyinSupport.isAsciiAlphaNumericQuery(text)) {
            return "";
        }
        return PinyinSupport.normalizeInput(text);
    }

    private static Map<String, List<LiteralIndexedTerm>> buildLiteralPrefixBuckets(List<LiteralIndexedTerm> terms) {
        Map<String, List<LiteralIndexedTerm>> buckets = new HashMap<>();
        for (LiteralIndexedTerm term : terms) {
            String key = term.literalKey();
            if (key.isBlank()) {
                continue;
            }
            String bucketKey = key.substring(0, Math.min(5, key.length()));
            List<LiteralIndexedTerm> bucket = buckets.computeIfAbsent(bucketKey, ignored -> new ArrayList<>());
            if (bucket.size() < 2048) {
                bucket.add(term);
            }
        }
        return buckets;
    }

    private static int compareLiteralTerms(LiteralIndexedTerm left, LiteralIndexedTerm right) {
        int docFreqCompare = Integer.compare(left.docFreq(), right.docFreq());
        if (docFreqCompare != 0) {
            return docFreqCompare;
        }
        return right.text().compareTo(left.text());
    }

    private PinyinFieldIndex buildPinyinFieldIndex(String field, boolean usePrecomputedTerms) throws IOException {
        return usePrecomputedTerms ? buildPrecomputedPinyinFieldIndex(field) : buildDirectPinyinFieldIndex(field);
    }

    private PinyinFieldIndex buildPrecomputedPinyinFieldIndex(String field) throws IOException {
        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return PinyinFieldIndex.EMPTY;
        }

        TermsEnum termsEnum = terms.iterator();
        if (termsEnum.seekCeil(new BytesRef(PinyinSupport.PRECOMPUTED_FULL_PREFIX)) == TermsEnum.SeekStatus.END) {
            return PinyinFieldIndex.EMPTY;
        }

        final int maxPinyinTerms = 300_000;
        PriorityQueue<PinyinIndexedTerm> retained = new PriorityQueue<>(Comparator
                .comparingInt(PinyinIndexedTerm::docFreq)
                .thenComparing(PinyinIndexedTerm::text));
        Map<String, Integer> surfaceDocFreqs = new HashMap<>();

        BytesRef current = termsEnum.term();
        while (current != null) {
            String encoded = current.utf8ToString();
            if (!PinyinSupport.isPrecomputedSuggestionTerm(encoded)) {
                break;
            }

            String surface = normalizeSuggestionSurface(PinyinSupport.decodePrecomputedSuggestionSurface(encoded));
            if (!PinyinSupport.shouldIndexTerm(surface) || containsWhitespace(surface)) {
                current = termsEnum.next();
                continue;
            }

            surfaceDocFreqs.merge(surface, termsEnum.docFreq(), Math::max);
            current = termsEnum.next();
        }

        for (Map.Entry<String, Integer> entry : surfaceDocFreqs.entrySet()) {
            PinyinIndexedTerm indexedTerm = new PinyinIndexedTerm(entry.getKey(), entry.getValue(), PinyinSupport.pinyinKey(entry.getKey()));
            if (indexedTerm.pinyinKey().isEmpty()) {
                continue;
            }
            if (retained.size() < maxPinyinTerms) {
                retained.add(indexedTerm);
                continue;
            }
            PinyinIndexedTerm smallest = retained.peek();
            if (smallest != null && compareIndexedTerms(indexedTerm, smallest) > 0) {
                retained.poll();
                retained.add(indexedTerm);
            }
        }

        List<PinyinIndexedTerm> termsList = retained.stream()
                .sorted(Comparator.comparingInt(PinyinIndexedTerm::docFreq).reversed().thenComparing(PinyinIndexedTerm::text))
                .toList();
        return new PinyinFieldIndex(
            buildExactPinyinPrefixBuckets(termsList, false),
            buildExactPinyinPrefixBuckets(termsList, true),
                buildPinyinPrefixBuckets(termsList, true),
                buildPinyinPrefixBuckets(termsList, false));
    }

    private PinyinFieldIndex buildDirectPinyinFieldIndex(String field) throws IOException {
        Terms terms = MultiTerms.getTerms(reader, field);
        if (terms == null) {
            return PinyinFieldIndex.EMPTY;
        }

        final int maxPinyinTerms = 200_000;
        PriorityQueue<PinyinIndexedTerm> retained = new PriorityQueue<>(Comparator
                .comparingInt(PinyinIndexedTerm::docFreq)
                .thenComparing(PinyinIndexedTerm::text));
        TermsEnum termsEnum = terms.iterator();
        BytesRef current;
        while ((current = termsEnum.next()) != null) {
            String normalized = normalizeSuggestionSurface(current.utf8ToString());
            if (!PinyinSupport.shouldIndexTerm(normalized) || containsWhitespace(normalized)) {
                continue;
            }
            PinyinIndexedTerm indexedTerm = new PinyinIndexedTerm(normalized, termsEnum.docFreq(), PinyinSupport.pinyinKey(normalized));
            if (indexedTerm.pinyinKey().isEmpty()) {
                continue;
            }
            if (retained.size() < maxPinyinTerms) {
                retained.add(indexedTerm);
                continue;
            }
            PinyinIndexedTerm smallest = retained.peek();
            if (smallest != null && compareIndexedTerms(indexedTerm, smallest) > 0) {
                retained.poll();
                retained.add(indexedTerm);
            }
        }

        List<PinyinIndexedTerm> termsList = retained.stream()
                .sorted(Comparator.comparingInt(PinyinIndexedTerm::docFreq).reversed().thenComparing(PinyinIndexedTerm::text))
                .toList();
        return new PinyinFieldIndex(
            buildExactPinyinPrefixBuckets(termsList, false),
            buildExactPinyinPrefixBuckets(termsList, true),
                buildPinyinPrefixBuckets(termsList, true),
                buildPinyinPrefixBuckets(termsList, false));
    }

    private static Map<String, List<PinyinIndexedTerm>> buildPinyinPrefixBuckets(
            List<PinyinIndexedTerm> terms,
            boolean fullPinyin) {
        Map<String, List<PinyinIndexedTerm>> buckets = new HashMap<>();
        for (PinyinIndexedTerm term : terms) {
            addPinyinPrefixes(buckets, PinyinSupport.bucketKeys(term.pinyinKey(), fullPinyin), term, COARSE_PINYIN_BUCKET_LIMIT);
        }
        return buckets;
    }

    private static Map<String, List<PinyinIndexedTerm>> buildExactPinyinPrefixBuckets(
            List<PinyinIndexedTerm> terms,
            boolean initialsOnly) {
        Map<String, List<PinyinIndexedTerm>> buckets = new HashMap<>();
        int maxPrefixLength = initialsOnly ? EXACT_INITIALS_PINYIN_PREFIX_LENGTH : EXACT_FULL_PINYIN_PREFIX_LENGTH;
        int minPrefixLength = initialsOnly ? EXACT_INITIALS_PINYIN_PREFIX_MIN_LENGTH : EXACT_FULL_PINYIN_PREFIX_MIN_LENGTH;
        for (PinyinIndexedTerm term : terms) {
            String key = initialsOnly ? term.pinyinKey().initials() : term.pinyinKey().full();
            if (key == null || key.isBlank()) {
                continue;
            }
            int effectiveMaxLength = Math.min(maxPrefixLength, key.length());
            if (effectiveMaxLength < minPrefixLength) {
                continue;
            }
            for (int length = minPrefixLength; length <= effectiveMaxLength; length++) {
                addPinyinPrefix(buckets, key.substring(0, length), term, exactPinyinBucketLimit(length, initialsOnly));
            }
        }
        return buckets;
    }

    private static void addPinyinPrefixes(
            Map<String, List<PinyinIndexedTerm>> buckets,
            List<String> keys,
            PinyinIndexedTerm term,
            int bucketLimit) {
        if (keys == null || keys.isEmpty()) {
            return;
        }

        for (String prefix : keys) {
            addPinyinPrefix(buckets, prefix, term, bucketLimit);
        }
    }

    private static void addPinyinPrefix(
            Map<String, List<PinyinIndexedTerm>> buckets,
            String prefix,
            PinyinIndexedTerm term,
            int bucketLimit) {
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        List<PinyinIndexedTerm> bucket = buckets.computeIfAbsent(prefix, ignored -> new ArrayList<>());
        if (bucket.size() < bucketLimit) {
            bucket.add(term);
        }
    }

    private static int exactPinyinBucketLimit(int prefixLength, boolean initialsOnly) {
        if (initialsOnly) {
            if (prefixLength >= 6) {
                return 32;
            }
            if (prefixLength >= 4) {
                return 48;
            }
            return 72;
        }
        if (prefixLength >= 7) {
            return 48;
        }
        if (prefixLength >= 5) {
            return 72;
        }
        return 96;
    }

    private static int compareIndexedTerms(PinyinIndexedTerm left, PinyinIndexedTerm right) {
        int docFreqCompare = Integer.compare(left.docFreq(), right.docFreq());
        if (docFreqCompare != 0) {
            return docFreqCompare;
        }
        return right.text().compareTo(left.text());
    }

    private boolean isSingleTokenTail(String tail) {
        return !tail.isBlank() && containsWhitespace(tail) == false;
    }

    private boolean shouldCollectCompactBigramMatches(String token) {
        String normalized = normalizeSuggestionSurface(token);
        if (normalized.isBlank() || containsWhitespace(normalized)) {
            return false;
        }
        return normalized.codePointCount(0, normalized.length()) >= 2;
    }

    private static boolean shouldIncludeNextTokenInAuto(String text) {
        String normalized = normalizeSuggestionSurface(text);
        if (normalized.isBlank()) {
            return false;
        }
        if (containsWhitespace(normalized)) {
            return true;
        }
        return normalized.codePointCount(0, normalized.length()) >= 2;
    }

    private static float autoNextTokenWeight(String text) {
        String normalized = normalizeSuggestionSurface(text);
        int codePointLength = normalized.codePointCount(0, normalized.length());
        boolean asciiOnly = normalized.chars().allMatch(ch -> ch < 128 || Character.isWhitespace(ch));
        if (codePointLength <= 2 && !asciiOnly) {
            return 0.9f;
        }
        return 0.96f;
    }

    private boolean isAcceptableNextTokenTail(String tail, int minCandidateLength) {
        if (tail == null) {
            return false;
        }

        String normalized = normalizeSuggestionSurface(tail);
        if (normalized.isBlank() || normalized.length() < minCandidateLength || !isSingleTokenTail(normalized)) {
            return false;
        }

        int codePointLength = normalized.codePointCount(0, normalized.length());
        boolean asciiOnly = normalized.chars().allMatch(ch -> ch < 128);
        if (asciiOnly) {
            if (codePointLength > 24) {
                return false;
            }
        } else if (codePointLength > 4) {
            return false;
        }

        return normalized.chars().anyMatch(LuceneIndexSuggester::isDisallowedNextTokenChar) == false;
    }

    private static String normalizeSuggestionSurface(String text) {
        return TextNormalization.normalizeSuggestionSurface(text);
    }

    private static boolean containsWhitespace(String text) {
        return TextNormalization.containsWhitespace(text);
    }

    private static boolean hasLeadingOrTrailingFunctionChar(String text) {
        return TopicQualityHeuristics.hasLeadingOrTrailingFunctionWord(text);
    }

    private static boolean isFunctionWordHeavy(String text) {
        return TopicQualityHeuristics.isFunctionWordHeavy(text);
    }

    private static boolean isCommonFunctionCodePoint(int codePoint) {
        return TopicQualityHeuristics.isFunctionWord(codePoint);
    }

    private static boolean isAcceptableCorrectionCandidate(String original, String suggestion) {
        if (suggestion == null || suggestion.isBlank()) {
            return false;
        }

        String normalizedOriginal = normalizeSuggestionSurface(original);
        String normalizedSuggestion = normalizeSuggestionSurface(suggestion);
        if (normalizedSuggestion.isBlank()) {
            return false;
        }

        boolean originalHasDigit = normalizedOriginal.chars().anyMatch(Character::isDigit);
        boolean suggestionHasDigit = normalizedSuggestion.chars().anyMatch(Character::isDigit);
        if (!originalHasDigit && suggestionHasDigit) {
            return false;
        }

        boolean originalAscii = normalizedOriginal.chars().allMatch(ch -> ch < 128 || Character.isWhitespace(ch));
        boolean suggestionAscii = normalizedSuggestion.chars().allMatch(ch -> ch < 128 || Character.isWhitespace(ch));
        if (!originalAscii && suggestionAscii) {
            return false;
        }

        int originalCodePoints = normalizedOriginal.codePointCount(0, normalizedOriginal.length());
        int suggestedCodePoints = normalizedSuggestion.codePointCount(0, normalizedSuggestion.length());
        if (!originalAscii && Math.abs(originalCodePoints - suggestedCodePoints) > 1) {
            return false;
        }

        return true;
    }

    private static boolean isShortCjkCorrectionToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        String normalized = normalizeSuggestionSurface(token);
        int codePointLength = normalized.codePointCount(0, normalized.length());
        if (codePointLength < 2 || codePointLength > 4) {
            return false;
        }
        if (normalized.chars().anyMatch(Character::isDigit)) {
            return false;
        }

        int nonAsciiCount = 0;
        for (int index = 0; index < normalized.length(); ) {
            int codePoint = normalized.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint >= 128) {
                nonAsciiCount++;
            }
        }
        return nonAsciiCount >= Math.max(1, codePointLength - 1);
    }

    private static String firstCodePointString(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int codePoint = text.codePointAt(0);
        return new String(Character.toChars(codePoint));
    }

    private static List<String> shortCjkPrefixes(String token) {
        List<String> prefixes = new ArrayList<>();
        String first = firstCodePointString(token);
        if (!first.isEmpty()) {
            int[] codePoints = token.codePoints().toArray();
            if (codePoints.length >= 3) {
                prefixes.add(new String(codePoints, 0, 2));
            }
            prefixes.add(first);
        }
        return prefixes;
    }

    private static int boundedCodePointEditDistance(String left, String right, int maxDistance) {
        int[] leftCodePoints = left.codePoints().toArray();
        int[] rightCodePoints = right.codePoints().toArray();
        if (Math.abs(leftCodePoints.length - rightCodePoints.length) > maxDistance) {
            return -1;
        }

        int[] previous = new int[rightCodePoints.length + 1];
        int[] current = new int[rightCodePoints.length + 1];
        for (int j = 0; j <= rightCodePoints.length; j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= leftCodePoints.length; i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= rightCodePoints.length; j++) {
                int substitutionCost = leftCodePoints[i - 1] == rightCodePoints[j - 1] ? 0 : 1;
                current[j] = Math.min(
                        Math.min(previous[j] + 1, current[j - 1] + 1),
                        previous[j - 1] + substitutionCost);
                rowMin = Math.min(rowMin, current[j]);
            }

            if (rowMin > maxDistance) {
                return -1;
            }

            int[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[rightCodePoints.length] <= maxDistance ? previous[rightCodePoints.length] : -1;
    }

    private static float shortCjkCorrectionScore(String original, String candidate, int docFreq, int distance) {
        float baseScore = distance == 0 ? 0.0f : (distance == 1 ? 3.5f : 1.75f);
        if (original.codePointCount(0, original.length()) == candidate.codePointCount(0, candidate.length())) {
            baseScore += 0.75f;
        }
        baseScore += Math.min(2, sharedLeadingCodePointCount(original, candidate)) * 0.9f;
        if (!original.isBlank() && !candidate.isBlank()) {
            int originalLast = original.codePointBefore(original.length());
            int candidateLast = candidate.codePointBefore(candidate.length());
            if (originalLast == candidateLast) {
                baseScore += 0.5f;
            }
        }
        return correctionScore(candidate, baseScore, docFreq);
    }

    private static int sharedLeadingCodePointCount(String left, String right) {
        int[] leftCodePoints = left.codePoints().toArray();
        int[] rightCodePoints = right.codePoints().toArray();
        int limit = Math.min(leftCodePoints.length, rightCodePoints.length);
        int shared = 0;
        while (shared < limit && leftCodePoints[shared] == rightCodePoints[shared]) {
            shared++;
        }
        return shared;
    }

    private static boolean isDisallowedNextTokenChar(int ch) {
        if (Character.isWhitespace(ch)) {
            return true;
        }

        return switch (Character.getType(ch)) {
            case Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION,
                    Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION,
                    Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION,
                    Character.MATH_SYMBOL,
                    Character.CURRENCY_SYMBOL,
                    Character.MODIFIER_SYMBOL,
                    Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }

    private int maxDocFreq(Collection<String> fields, String token) throws IOException {
        int maxDocFreq = 0;
        for (String field : fields) {
            maxDocFreq = Math.max(maxDocFreq, docFreq(field, token));
        }
        return maxDocFreq;
    }

    private int docFreq(String field, String token) throws IOException {
        if (field == null || token == null || token.isBlank()) {
            return 0;
        }
        return reader.docFreq(new Term(field, token));
    }

    public record Correction(
            String original,
            String suggested,
            int originalDocFreq,
            int suggestedDocFreq,
            float score,
            boolean changed) {

        public static Correction unchanged(String token, int docFreq) {
            return new Correction(token, token, docFreq, docFreq, 0.0f, false);
        }
    }

    public record CompletionCandidate(String text, int docFreq, float score, CompletionType type) {
    }

    public record SuggestionOption(String text, int docFreq, float score, String type) {
    }

    public enum CompletionType {
        PREFIX,
        NEXT_TOKEN
    }

    public record CorrectionConfig(
            int rareTermDocFreq,
            int minTokenLength,
            int maxEdits,
            int prefixLength,
            int maxSuggestions,
            int minCandidateDocFreq,
            float accuracy,
            boolean usePinyin) {

        public CorrectionConfig(
                int rareTermDocFreq,
                int minTokenLength,
                int maxEdits,
                int prefixLength,
                int maxSuggestions,
                int minCandidateDocFreq,
                float accuracy) {
            this(rareTermDocFreq, minTokenLength, maxEdits, prefixLength, maxSuggestions, minCandidateDocFreq, accuracy, false);
        }

        public static CorrectionConfig defaults() {
            return new CorrectionConfig(0, 4, 2, 1, 3, 1, 0.5f, false);
        }

        public CorrectionConfig {
            if (rareTermDocFreq < 0) {
                throw new IllegalArgumentException("rareTermDocFreq must be >= 0");
            }
            if (minTokenLength < 1) {
                throw new IllegalArgumentException("minTokenLength must be >= 1");
            }
            if (maxEdits < 1 || maxEdits > 2) {
                throw new IllegalArgumentException("maxEdits must be 1 or 2");
            }
            if (prefixLength < 0) {
                throw new IllegalArgumentException("prefixLength must be >= 0");
            }
            if (maxSuggestions < 1) {
                throw new IllegalArgumentException("maxSuggestions must be >= 1");
            }
            if (minCandidateDocFreq < 1) {
                throw new IllegalArgumentException("minCandidateDocFreq must be >= 1");
            }
            if (accuracy <= 0.0f || accuracy > 1.0f) {
                throw new IllegalArgumentException("accuracy must be in (0, 1]");
            }
        }
    }

    public record CompletionConfig(
            int size,
            int scanLimit,
            int minPrefixLength,
            int minCandidateLength,
            boolean allowCompactBigrams,
            boolean usePinyin) {

        public CompletionConfig(
                int size,
                int scanLimit,
                int minPrefixLength,
                int minCandidateLength,
                boolean allowCompactBigrams) {
            this(size, scanLimit, minPrefixLength, minCandidateLength, allowCompactBigrams, false);
        }

        public static CompletionConfig defaults() {
            return new CompletionConfig(5, 64, 1, 1, true, false);
        }

        public CompletionConfig {
            if (size < 1) {
                throw new IllegalArgumentException("size must be >= 1");
            }
            if (scanLimit < 1) {
                throw new IllegalArgumentException("scanLimit must be >= 1");
            }
            if (minPrefixLength < 1) {
                throw new IllegalArgumentException("minPrefixLength must be >= 1");
            }
            if (minCandidateLength < 1) {
                throw new IllegalArgumentException("minCandidateLength must be >= 1");
            }
        }
    }

    private static final class CorrectionCandidateAccumulator {
        private static final Comparator<CorrectionCandidateAccumulator> ORDER = Comparator
                .comparingDouble(CorrectionCandidateAccumulator::bestScore).reversed()
                .thenComparing(Comparator.comparingInt(CorrectionCandidateAccumulator::maxDocFreq).reversed())
                .thenComparing(Comparator.comparingInt(CorrectionCandidateAccumulator::matchingFields).reversed())
                .thenComparing(CorrectionCandidateAccumulator::text);

        private final String text;
        private final LinkedHashSet<String> fields = new LinkedHashSet<>();
        private float bestScore;
        private int maxDocFreq;

        private CorrectionCandidateAccumulator(String text) {
            this.text = text;
        }

        private void add(String field, SuggestWord suggestWord, int docFreq) {
            fields.add(field);
            bestScore = Math.max(bestScore, correctionScore(suggestWord.string, suggestWord.score, docFreq));
            maxDocFreq = Math.max(maxDocFreq, docFreq);
        }

        private void add(String field, String text, float score, int docFreq) {
            fields.add(field);
            bestScore = Math.max(bestScore, score);
            maxDocFreq = Math.max(maxDocFreq, docFreq);
        }

        private float bestScore() {
            return bestScore;
        }

        private int maxDocFreq() {
            return maxDocFreq;
        }

        private int matchingFields() {
            return fields.size();
        }

        private String text() {
            return text;
        }

        private Correction toCorrection(String original, int originalDocFreq) {
            return new Correction(original, text, originalDocFreq, maxDocFreq, bestScore, true);
        }
    }

    private record PhraseCandidate(
            String text,
            float totalScore,
            int aggregateDocFreq,
            int changedCount) {

        private static final Comparator<PhraseCandidate> ORDER = Comparator
            .comparingDouble(PhraseCandidate::rankingScore).reversed()
                .thenComparing(Comparator.comparingInt(PhraseCandidate::effectiveDocFreq).reversed())
                .thenComparingInt(PhraseCandidate::changedCount)
                .thenComparing(PhraseCandidate::text);

        private static PhraseCandidate empty() {
            return new PhraseCandidate("", 0.0f, Integer.MAX_VALUE, 0);
        }

        private PhraseCandidate append(Correction correction) {
            String nextText = text.isEmpty() ? correction.suggested() : text + " " + correction.suggested();
            nextText = normalizeSuggestionSurface(nextText);
            if (!correction.changed()) {
                return new PhraseCandidate(nextText, totalScore, aggregateDocFreq, changedCount);
            }
            int nextDocFreq = Math.min(aggregateDocFreq, correction.suggestedDocFreq());
            return new PhraseCandidate(nextText, totalScore + correction.score(), nextDocFreq, changedCount + 1);
        }

        private boolean changed() {
            return changedCount > 0;
        }

        private float averageScore() {
            return changedCount == 0 ? 0.0f : totalScore / changedCount;
        }

        private float rankingScore() {
            float score = averageScore();
            int effectiveDocFreq = effectiveDocFreq();
            if (effectiveDocFreq > 0) {
                score += (float) (Math.log1p(effectiveDocFreq) * 0.2d);
            }
            return score;
        }

        private int effectiveDocFreq() {
            return aggregateDocFreq == Integer.MAX_VALUE ? 0 : aggregateDocFreq;
        }

        private SuggestionOption toSuggestionOption() {
            return new SuggestionOption(normalizeSuggestionSurface(text), effectiveDocFreq(), rankingScore(), "correction");
        }
    }

    private static final class CompletionAccumulator {
        private static final Comparator<CompletionAccumulator> ORDER = Comparator
                .comparingDouble(CompletionAccumulator::score).reversed()
                .thenComparing(Comparator.comparingInt(CompletionAccumulator::docFreq).reversed())
                .thenComparing(CompletionAccumulator::type)
                .thenComparing(CompletionAccumulator::text);

        private final String text;
        private int docFreq;
        private int tailDocFreq;
        private CompletionType type = CompletionType.PREFIX;
        private float score;

        private CompletionAccumulator(String text) {
            this.text = text;
        }

        private void add(int docFreq, int tailDocFreq, CompletionType type, float score) {
            if (docFreq > this.docFreq) {
                this.docFreq = docFreq;
            }
            if (score > this.score) {
                this.score = score;
            }
            if (type == CompletionType.NEXT_TOKEN) {
                this.type = CompletionType.NEXT_TOKEN;
                if (tailDocFreq > 0 && (this.tailDocFreq == 0 || tailDocFreq < this.tailDocFreq)) {
                    this.tailDocFreq = tailDocFreq;
                }
            }
        }

        private int docFreq() {
            return docFreq;
        }

        private CompletionType type() {
            return type;
        }

        private float score() {
            return score;
        }

        private String text() {
            return text;
        }

        private CompletionCandidate toCandidate() {
            return new CompletionCandidate(text, docFreq, score(), type);
        }
    }

    private record PinyinIndexedTerm(String text, int docFreq, PinyinSupport.PinyinKey pinyinKey) {
    }

    private record LiteralIndexedTerm(String text, int docFreq, String literalKey) {
    }

    private record PinyinFieldIndex(
            Map<String, List<PinyinIndexedTerm>> exactFullPrefixBuckets,
            Map<String, List<PinyinIndexedTerm>> exactInitialsPrefixBuckets,
            Map<String, List<PinyinIndexedTerm>> fullPrefixBuckets,
            Map<String, List<PinyinIndexedTerm>> initialsPrefixBuckets) {
        private static final PinyinFieldIndex EMPTY = new PinyinFieldIndex(Map.of(), Map.of(), Map.of(), Map.of());

        private List<PinyinIndexedTerm> candidates(String input) {
            if (exactFullPrefixBuckets.isEmpty() && exactInitialsPrefixBuckets.isEmpty()
                    && fullPrefixBuckets.isEmpty() && initialsPrefixBuckets.isEmpty()) {
                return List.of();
            }

            PinyinSupport.PinyinKey inputKey = PinyinSupport.pinyinKey(input);
            if (inputKey.isEmpty()) {
                return List.of();
            }

            LinkedHashMap<String, PinyinIndexedTerm> merged = new LinkedHashMap<>();
            addCandidates(merged, exactBucket(exactFullPrefixBuckets, inputKey.full()));
            if (PinyinSupport.shouldUseInitialsBuckets(input)) {
                addCandidates(merged, exactBucket(exactInitialsPrefixBuckets, inputKey.initials()));
            }
            if (merged.size() < EXACT_PREFIX_COARSE_FALLBACK_THRESHOLD) {
                addCandidates(merged, bucket(fullPrefixBuckets, inputKey.full()));
                if (PinyinSupport.shouldUseInitialsBuckets(input)) {
                    addCandidates(merged, bucket(initialsPrefixBuckets, inputKey.initials()));
                }
            }

            String normalizedInput = PinyinSupport.normalizeInput(input);
            if (merged.isEmpty() && !normalizedInput.isEmpty()) {
                addCandidates(merged, exactBucket(exactFullPrefixBuckets, normalizedInput));
                if (PinyinSupport.shouldUseInitialsBuckets(input)) {
                    addCandidates(merged, exactBucket(exactInitialsPrefixBuckets, normalizedInput));
                }
                if (merged.size() < EXACT_PREFIX_COARSE_FALLBACK_THRESHOLD) {
                    addCandidates(merged, bucket(fullPrefixBuckets, normalizedInput));
                    if (PinyinSupport.shouldUseInitialsBuckets(input)) {
                        addCandidates(merged, bucket(initialsPrefixBuckets, normalizedInput));
                    }
                }
            }
            return List.copyOf(merged.values());
        }

        private static void addCandidates(
                LinkedHashMap<String, PinyinIndexedTerm> merged,
                List<PinyinIndexedTerm> candidates) {
            for (PinyinIndexedTerm candidate : candidates) {
                merged.putIfAbsent(candidate.text(), candidate);
            }
        }

        private static List<PinyinIndexedTerm> bucket(
                Map<String, List<PinyinIndexedTerm>> buckets,
                String queryKey) {
            if (queryKey == null || queryKey.isBlank()) {
                return List.of();
            }
            int anchorLength = Math.min(4, queryKey.length());
            return buckets.getOrDefault(queryKey.substring(0, anchorLength), List.of());
        }

        private static List<PinyinIndexedTerm> exactBucket(
                Map<String, List<PinyinIndexedTerm>> buckets,
                String queryKey) {
            if (queryKey == null || queryKey.isBlank()) {
                return List.of();
            }
            return buckets.getOrDefault(queryKey, List.of());
        }
    }

    private record LiteralFieldIndex(
            List<LiteralIndexedTerm> terms,
            Map<String, List<LiteralIndexedTerm>> prefixBuckets) {
        private static final LiteralFieldIndex EMPTY = new LiteralFieldIndex(List.of(), Map.of());

        private List<LiteralIndexedTerm> candidates(String inputKey) {
            if (terms.isEmpty() || inputKey == null || inputKey.isBlank()) {
                return List.of();
            }
            String bucketKey = inputKey.substring(0, Math.min(5, inputKey.length()));
            return prefixBuckets.getOrDefault(bucketKey, List.of());
        }
    }

    private static final class PinyinIndexCache {
        private static final int MAX_READERS = 128;
        private static final Map<Object, Map<String, PinyinFieldIndex>> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Object, Map<String, PinyinFieldIndex>> eldest) {
                return size() > MAX_READERS;
            }
        };

        private static PinyinFieldIndex getOrBuild(Object readerKey, String field, PinyinFieldIndexLoader loader)
                throws IOException {
            synchronized (CACHE) {
                Map<String, PinyinFieldIndex> byField = CACHE.computeIfAbsent(readerKey, ignored -> new HashMap<>());
                PinyinFieldIndex cached = byField.get(field);
                if (cached != null) {
                    return cached;
                }
            }

            PinyinFieldIndex built = loader.load();
            synchronized (CACHE) {
                Map<String, PinyinFieldIndex> byField = CACHE.computeIfAbsent(readerKey, ignored -> new HashMap<>());
                PinyinFieldIndex cached = byField.get(field);
                if (cached != null) {
                    return cached;
                }
                byField.put(field, built);
                return built;
            }
        }
    }

    private static final class LiteralPrefixIndexCache {
        private static final int MAX_READERS = 128;
        private static final Map<Object, Map<String, LiteralFieldIndex>> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Object, Map<String, LiteralFieldIndex>> eldest) {
                return size() > MAX_READERS;
            }
        };

        private static LiteralFieldIndex getOrBuild(Object readerKey, String field, LiteralFieldIndexLoader loader)
                throws IOException {
            synchronized (CACHE) {
                Map<String, LiteralFieldIndex> byField = CACHE.computeIfAbsent(readerKey, ignored -> new HashMap<>());
                LiteralFieldIndex cached = byField.get(field);
                if (cached != null) {
                    return cached;
                }
            }

            LiteralFieldIndex built = loader.load();
            synchronized (CACHE) {
                Map<String, LiteralFieldIndex> byField = CACHE.computeIfAbsent(readerKey, ignored -> new HashMap<>());
                LiteralFieldIndex cached = byField.get(field);
                if (cached != null) {
                    return cached;
                }
                byField.put(field, built);
                return built;
            }
        }
    }

    @FunctionalInterface
    private interface PinyinFieldIndexLoader {
        PinyinFieldIndex load() throws IOException;
    }

    @FunctionalInterface
    private interface LiteralFieldIndexLoader {
        LiteralFieldIndex load() throws IOException;
    }

    private static final class AutoSuggestionAccumulator {
        private static final Comparator<AutoSuggestionAccumulator> ORDER = Comparator
                .comparingDouble(AutoSuggestionAccumulator::rankingScore).reversed()
                .thenComparing(Comparator.comparingInt(AutoSuggestionAccumulator::docFreq).reversed())
                .thenComparing(AutoSuggestionAccumulator::type)
                .thenComparing(AutoSuggestionAccumulator::text);

        private final String text;
        private final LinkedHashSet<String> sources = new LinkedHashSet<>();
        private int docFreq;
        private float totalScore;
        private float bestBranchScore;
        private String type = "prefix";

        private AutoSuggestionAccumulator(String text) {
            this.text = text;
        }

        private void add(String suggestionText, int suggestionDocFreq, float score, String suggestionType) {
            sources.add(suggestionType);
            if (suggestionDocFreq > docFreq) {
                docFreq = suggestionDocFreq;
            }
            totalScore += score;
            if (score > bestBranchScore) {
                bestBranchScore = score;
                type = suggestionType;
            }
        }

        private float rankingScore() {
            return totalScore + Math.max(0, sources.size() - 1) * 6.0f;
        }

        private int docFreq() {
            return docFreq;
        }

        private String type() {
            return sources.size() > 1 ? "auto" : type;
        }

        private String text() {
            return text;
        }

        private SuggestionOption toSuggestionOption() {
            return new SuggestionOption(text, docFreq, rankingScore(), type());
        }
    }

    private static float correctionScore(String text, float baseScore, int docFreq) {
        float score = baseScore + (float) (Math.log1p(docFreq) * 0.35d);
        String normalized = normalizeSuggestionSurface(text);
        int codePointLength = normalized.codePointCount(0, normalized.length());
        if (containsWhitespace(normalized)) {
            score *= 0.82f;
        }
        if (codePointLength == 1) {
            score *= 0.7f;
        }
        if (hasLeadingOrTrailingFunctionChar(normalized)) {
            score *= 0.85f;
        }
        return score;
    }
}