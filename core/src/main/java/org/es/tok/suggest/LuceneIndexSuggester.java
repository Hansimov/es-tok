package org.es.tok.suggest;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Index-level spell-correction and completion helper backed by Lucene terms.
 * <p>
 * This class only touches the term dictionary and doc frequencies. It never
 * scans documents, which keeps it viable for large indexes as long as the
 * number of candidate expansions is bounded.
 */
public class LuceneIndexSuggester {

    private final IndexReader reader;

    public LuceneIndexSuggester(IndexReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
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

        DirectSpellChecker checker = createSpellChecker(effectiveConfig);
        Map<String, CorrectionCandidateAccumulator> candidates = new HashMap<>();

        for (String field : normalizedFields) {
            SuggestWord[] words = checker.suggestSimilar(
                    new Term(field, token),
                    effectiveConfig.maxSuggestions(),
                    reader,
                    SuggestMode.SUGGEST_MORE_POPULAR);

            for (SuggestWord word : words) {
                if (word == null || word.string == null || token.equals(word.string)) {
                    continue;
                }
                CorrectionCandidateAccumulator accumulator = candidates.computeIfAbsent(
                        word.string,
                        ignored -> new CorrectionCandidateAccumulator(word.string));
                accumulator.add(field, word, docFreq(field, word.string));
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
        for (String field : normalizeFields(fields)) {
            collectPrefixMatches(field, prefix, effectiveConfig, candidates);
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
            if (effectiveConfig.allowCompactBigrams()) {
                collectCompactBigramMatches(field, token, effectiveConfig, candidates);
            }
        }
        return finalizeCompletions(candidates, effectiveConfig.size());
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
            if (text.length() >= config.minCandidateLength()) {
                addCompletionCandidate(candidates, text, termsEnum.docFreq(), CompletionType.PREFIX);
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
            String tail = text.substring(prefix.length()).trim();
            if (isSingleTokenTail(tail) && tail.length() >= config.minCandidateLength()) {
                addCompletionCandidate(candidates, tail, termsEnum.docFreq(), CompletionType.NEXT_TOKEN);
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
            if (text.indexOf(' ') >= 0 || text.length() <= token.length()) {
                current = termsEnum.next();
                continue;
            }

            String tail = text.substring(token.length());
            if (tail.length() >= config.minCandidateLength() && docFreq(field, tail) > 0) {
                addCompletionCandidate(candidates, tail, termsEnum.docFreq(), CompletionType.NEXT_TOKEN);
            }
            current = termsEnum.next();
        }
    }

    private void addCompletionCandidate(
            Map<String, CompletionAccumulator> candidates,
            String text,
            int docFreq,
            CompletionType type) {
        CompletionAccumulator accumulator = candidates.computeIfAbsent(text, CompletionAccumulator::new);
        accumulator.add(docFreq, type);
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

    private boolean isSingleTokenTail(String tail) {
        return !tail.isBlank() && tail.indexOf(' ') < 0;
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
            float accuracy) {

        public static CorrectionConfig defaults() {
            return new CorrectionConfig(0, 4, 2, 1, 3, 1, 0.5f);
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
            boolean allowCompactBigrams) {

        public static CompletionConfig defaults() {
            return new CompletionConfig(5, 64, 1, 1, true);
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
            bestScore = Math.max(bestScore, suggestWord.score);
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
                .comparingDouble(PhraseCandidate::averageScore).reversed()
                .thenComparing(Comparator.comparingInt(PhraseCandidate::effectiveDocFreq).reversed())
                .thenComparing(Comparator.comparingInt(PhraseCandidate::changedCount).reversed())
                .thenComparing(PhraseCandidate::text);

        private static PhraseCandidate empty() {
            return new PhraseCandidate("", 0.0f, Integer.MAX_VALUE, 0);
        }

        private PhraseCandidate append(Correction correction) {
            String nextText = text.isEmpty() ? correction.suggested() : text + " " + correction.suggested();
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

        private int effectiveDocFreq() {
            return aggregateDocFreq == Integer.MAX_VALUE ? 0 : aggregateDocFreq;
        }

        private SuggestionOption toSuggestionOption() {
            return new SuggestionOption(text, effectiveDocFreq(), averageScore(), "correction");
        }
    }

    private static final class CompletionAccumulator {
        private static final Comparator<CompletionAccumulator> ORDER = Comparator
                .comparingInt(CompletionAccumulator::docFreq).reversed()
                .thenComparing(CompletionAccumulator::type)
                .thenComparing(CompletionAccumulator::text);

        private final String text;
        private int docFreq;
        private CompletionType type = CompletionType.PREFIX;

        private CompletionAccumulator(String text) {
            this.text = text;
        }

        private void add(int docFreq, CompletionType type) {
            if (docFreq > this.docFreq) {
                this.docFreq = docFreq;
            }
            if (type == CompletionType.NEXT_TOKEN) {
                this.type = CompletionType.NEXT_TOKEN;
            }
        }

        private int docFreq() {
            return docFreq;
        }

        private CompletionType type() {
            return type;
        }

        private String text() {
            return text;
        }

        private CompletionCandidate toCandidate() {
            float score = docFreq;
            if (type == CompletionType.NEXT_TOKEN) {
                score += 0.25f;
            }
            return new CompletionCandidate(text, docFreq, score, type);
        }
    }
}