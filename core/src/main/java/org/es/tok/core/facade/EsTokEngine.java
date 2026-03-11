package org.es.tok.core.facade;

import org.es.tok.config.EsTokConfig;
import org.es.tok.core.model.AnalysisVersion;
import org.es.tok.core.model.AnalyzeRequest;
import org.es.tok.core.model.AnalyzeResult;
import org.es.tok.core.model.AnalyzeToken;
import org.es.tok.extra.HantToHansConverter;
import org.es.tok.rules.AnalyzeRules;
import org.es.tok.rules.RulesConfig;
import org.es.tok.strategy.CategStrategy;
import org.es.tok.strategy.NgramStrategy;
import org.es.tok.strategy.TokenStrategy;
import org.es.tok.strategy.VocabStrategy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class EsTokEngine {
    private final EsTokConfig config;
    private final VocabStrategy vocabStrategy;
    private final CategStrategy categStrategy;
    private final NgramStrategy ngramStrategy;
    private final HantToHansConverter hantToHansConverter;

    public EsTokEngine(EsTokConfig config) {
        this(
                config,
                config.getVocabConfig().getOrCreateStrategy(),
                config.getCategConfig().isUseCateg() ? new CategStrategy(config.getCategConfig().isSplitWord()) : null,
                config.getNgramConfig().hasAnyNgramEnabled() ? new NgramStrategy(config.getNgramConfig()) : null,
                createConverter(config));
    }

    public EsTokEngine(EsTokConfig config, VocabStrategy vocabStrategy, CategStrategy categStrategy,
            NgramStrategy ngramStrategy, HantToHansConverter hantToHansConverter) {
        this.config = config;
        this.vocabStrategy = vocabStrategy;
        this.categStrategy = categStrategy;
        this.ngramStrategy = ngramStrategy;
        this.hantToHansConverter = hantToHansConverter;

        if (vocabStrategy == null && categStrategy == null) {
            throw new IllegalArgumentException("Must use at least one strategy: use_vocab, use_categ");
        }
    }

    public AnalyzeResult analyze(AnalyzeRequest request) {
        return analyze(request.getText());
    }

    public AnalyzeResult analyze(String text) {
        List<TokenStrategy.TokenInfo> tokenInfos = analyzeToTokenInfos(text);
        List<AnalyzeToken> tokens = new ArrayList<>(tokenInfos.size());
        for (int index = 0; index < tokenInfos.size(); index++) {
            TokenStrategy.TokenInfo token = tokenInfos.get(index);
            tokens.add(new AnalyzeToken(
                    token.getText(),
                    token.getStartOffset(),
                    token.getEndOffset(),
                    token.getType(),
                    token.getGroup(),
                    index));
        }
        return new AnalyzeResult(tokens, resolveVersion());
    }

    public List<TokenStrategy.TokenInfo> analyzeToTokenInfos(String text) {
        if (text == null) {
            return List.of();
        }

        String processed = text;
        if (config.getExtraConfig().isIgnoreCase()) {
            processed = processed.toLowerCase();
        }
        if (config.getExtraConfig().isIgnoreHant() && hantToHansConverter != null) {
            processed = hantToHansConverter.convert(processed);
        }

        List<TokenStrategy.TokenInfo> baseTokens = generateBaseTokens(processed);

        if (config.getExtraConfig().isDropDuplicates()) {
            baseTokens = dropDuplicatedTokens(baseTokens);
        }

        baseTokens = sortTokensByOffset(baseTokens);

        List<TokenStrategy.TokenInfo> ngramTokens = new ArrayList<>();
        if (ngramStrategy != null) {
            ngramTokens = ngramStrategy.generateNgrams(baseTokens);
        }

        if (config.getExtraConfig().isDropCategs()) {
            baseTokens = dropCategTokens(baseTokens, 2);
        }

        List<TokenStrategy.TokenInfo> allTokens = new ArrayList<>(baseTokens);
        allTokens.addAll(ngramTokens);

        if (config.getExtraConfig().isDropDuplicates()) {
            allTokens = dropDuplicatedTokens(allTokens);
        }

        allTokens = sortTokensByOffset(allTokens);

        if (config.getRulesConfig() != null && config.getRulesConfig().hasActiveRules()) {
            allTokens = applyRulesFilter(allTokens);
        }

        return allTokens;
    }

    public AnalysisVersion resolveVersion() {
        String vocabHash = hashStrings(config.getVocabConfig().getVocabs());
        String rulesHash = hashRules(config.getRulesConfig());
        String analysisHash = hashString(String.join("|",
                Boolean.toString(config.getExtraConfig().isIgnoreCase()),
                Boolean.toString(config.getExtraConfig().isIgnoreHant()),
                Boolean.toString(config.getExtraConfig().isDropDuplicates()),
                Boolean.toString(config.getExtraConfig().isDropCategs()),
                Boolean.toString(config.getExtraConfig().isDropVocabs()),
                Boolean.toString(config.getExtraConfig().isEmitPinyinTerms()),
                Boolean.toString(config.getCategConfig().isUseCateg()),
                Boolean.toString(config.getCategConfig().isSplitWord()),
                Boolean.toString(config.getVocabConfig().isUseVocab()),
                Boolean.toString(config.getNgramConfig().isUseNgram()),
                Boolean.toString(config.getNgramConfig().isUseBigram()),
                Boolean.toString(config.getNgramConfig().isUseVcgram()),
                Boolean.toString(config.getNgramConfig().isUseVbgram()),
                Boolean.toString(config.getNgramConfig().isDropCogram()),
                vocabHash,
                rulesHash));
        return new AnalysisVersion(analysisHash, vocabHash, rulesHash);
    }

    private static HantToHansConverter createConverter(EsTokConfig config) {
        if (!config.getExtraConfig().isIgnoreHant()) {
            return null;
        }
        try {
            return HantToHansConverter.getInstance();
        } catch (IOException e) {
            return null;
        }
    }

    private List<TokenStrategy.TokenInfo> applyRulesFilter(List<TokenStrategy.TokenInfo> tokens) {
        AnalyzeRules rules = config.getRulesConfig().getAnalyzeRules();
        Set<String> allTokenTexts = new HashSet<>();
        for (TokenStrategy.TokenInfo token : tokens) {
            allTokenTexts.add(token.getText());
        }

        List<TokenStrategy.TokenInfo> filtered = new ArrayList<>();
        for (TokenStrategy.TokenInfo token : tokens) {
            if (!rules.shouldExclude(token.getText(), allTokenTexts)) {
                filtered.add(token);
            }
        }
        return filtered;
    }

    private List<TokenStrategy.TokenInfo> generateBaseTokens(String text) {
        List<TokenStrategy.TokenInfo> baseTokens = new ArrayList<>();
        if (config.getCategConfig().isUseCateg() && categStrategy != null) {
            baseTokens.addAll(categStrategy.tokenize(text));
        }
        if (config.getVocabConfig().isUseVocab() && vocabStrategy != null) {
            baseTokens.addAll(vocabStrategy.tokenize(text));
        }
        return baseTokens;
    }

    private List<TokenStrategy.TokenInfo> sortTokensByOffset(List<TokenStrategy.TokenInfo> tokens) {
        List<TokenStrategy.TokenInfo> sortedTokens = new ArrayList<>(tokens);
        sortedTokens.sort((a, b) -> {
            int offsetCompare = Integer.compare(a.getStartOffset(), b.getStartOffset());
            if (offsetCompare != 0) {
                return offsetCompare;
            }

            int endOffsetCompare = Integer.compare(a.getEndOffset(), b.getEndOffset());
            if (endOffsetCompare != 0) {
                return endOffsetCompare;
            }

            return a.getText().compareTo(b.getText());
        });

        return sortedTokens;
    }

    private List<TokenStrategy.TokenInfo> dropDuplicatedTokens(List<TokenStrategy.TokenInfo> tokens) {
        Set<TokenKey> seenTokens = new LinkedHashSet<>();
        List<TokenStrategy.TokenInfo> uniqueTokens = new ArrayList<>();

        for (TokenStrategy.TokenInfo token : tokens) {
            TokenKey key = new TokenKey(token.getText(), token.getStartOffset(), token.getEndOffset());
            if (seenTokens.add(key)) {
                uniqueTokens.add(token);
            }
        }

        return uniqueTokens;
    }

    private List<TokenStrategy.TokenInfo> dropCategTokens(List<TokenStrategy.TokenInfo> tokens, int vocabFreqThreshold) {
        List<TokenStrategy.TokenInfo> sortedTokens = sortTokensByOffset(tokens);
        SeparatorOffsets separatorOffsets = computeSeparatorOffsets(tokens);
        Iterator<TokenStrategy.TokenInfo> iterator = tokens.iterator();
        while (iterator.hasNext()) {
            TokenStrategy.TokenInfo token = iterator.next();
            if (!isTokenGroup(token, "categ")) {
                continue;
            }
            String tokenType = token.getType();
            if (!"cjk".equals(tokenType) && !"lang".equals(tokenType)) {
                continue;
            }

            int vocabFreq = 0;
            int categStart = token.getStartOffset();
            boolean shouldRemoveToken = false;
            for (int index = 0; index < sortedTokens.size(); index++) {
                TokenStrategy.TokenInfo vocabToken = sortedTokens.get(index);
                if (vocabToken.getStartOffset() > categStart) {
                    break;
                }
                if (isTokenGroup(vocabToken, "vocab") && isContainedIn(token, vocabToken)) {
                    vocabFreq++;
                    if (vocabFreq >= vocabFreqThreshold || isBoundaryToken(vocabToken, separatorOffsets)) {
                        shouldRemoveToken = true;
                        break;
                    }
                }
            }
            if (shouldRemoveToken) {
                iterator.remove();
            }
        }

        tokens.removeIf(token -> isTokenGroup(token, "categ") && isSeparatorType(token.getType()));
        return tokens;
    }

    private boolean isTokenGroup(TokenStrategy.TokenInfo token, String group) {
        return group.equals(token.getGroup());
    }

    private boolean isContainedIn(TokenStrategy.TokenInfo token1, TokenStrategy.TokenInfo token2) {
        return token2.getStartOffset() <= token1.getStartOffset() && token1.getEndOffset() <= token2.getEndOffset();
    }

    private SeparatorOffsets computeSeparatorOffsets(List<TokenStrategy.TokenInfo> tokens) {
        Set<Integer> sepStartOffsets = new HashSet<>();
        Set<Integer> sepEndOffsets = new HashSet<>();
        int maxEndOffset = 0;
        boolean hasTokens = false;

        for (TokenStrategy.TokenInfo token : tokens) {
            hasTokens = true;
            maxEndOffset = Math.max(maxEndOffset, token.getEndOffset());
            if (isSeparatorType(token.getType())) {
                int startOffset = token.getStartOffset();
                int endOffset = token.getEndOffset();
                sepStartOffsets.add(endOffset - 1);
                sepEndOffsets.add(startOffset + 1);
            }
        }

        sepStartOffsets.add(-1);
        sepEndOffsets.add((hasTokens ? maxEndOffset : 0) + 1);

        return new SeparatorOffsets(sepStartOffsets, sepEndOffsets);
    }

    private boolean isSeparatorType(String type) {
        return "ws".equals(type) || "dash".equals(type) || "mask".equals(type) || "nord".equals(type);
    }

    private boolean isBoundaryToken(TokenStrategy.TokenInfo token, SeparatorOffsets separatorOffsets) {
        int startBoundary = token.getStartOffset() - 1;
        int endBoundary = token.getEndOffset() + 1;
        return separatorOffsets.isSepStartOffset(startBoundary) || separatorOffsets.isSepEndOffset(endBoundary);
    }

    private String hashStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "disabled";
        }
        return hashString(String.join("\u001f", values));
    }

    private String hashRules(RulesConfig rulesConfig) {
        if (rulesConfig == null || !rulesConfig.hasActiveRules()) {
            return "disabled";
        }
        AnalyzeRules rules = rulesConfig.getAnalyzeRules();
        return hashString(String.join("|",
                String.join("\u001f", rules.getExcludeTokens()),
                String.join("\u001f", rules.getExcludePrefixes()),
                String.join("\u001f", rules.getExcludeSuffixes()),
                String.join("\u001f", rules.getExcludeContains()),
                String.join("\u001f", rules.getExcludePatterns()),
                String.join("\u001f", rules.getIncludeTokens()),
                String.join("\u001f", rules.getIncludePrefixes()),
                String.join("\u001f", rules.getIncludeSuffixes()),
                String.join("\u001f", rules.getIncludeContains()),
                String.join("\u001f", rules.getIncludePatterns()),
                String.join("\u001f", rules.getDecludePrefixes()),
                String.join("\u001f", rules.getDecludeSuffixes())));
    }

    private String hashString(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                builder.append(String.format("%02x", hash[index]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static final class TokenKey {
        private final String text;
        private final int startOffset;
        private final int endOffset;

        private TokenKey(String text, int startOffset, int endOffset) {
            this.text = text;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            TokenKey tokenKey = (TokenKey) obj;
            return startOffset == tokenKey.startOffset && endOffset == tokenKey.endOffset && text.equals(tokenKey.text);
        }

        @Override
        public int hashCode() {
            int result = text.hashCode();
            result = 31 * result + startOffset;
            result = 31 * result + endOffset;
            return result;
        }
    }

    private static final class SeparatorOffsets {
        private final Set<Integer> sepStartOffsets;
        private final Set<Integer> sepEndOffsets;

        private SeparatorOffsets(Set<Integer> sepStartOffsets, Set<Integer> sepEndOffsets) {
            this.sepStartOffsets = sepStartOffsets;
            this.sepEndOffsets = sepEndOffsets;
        }

        private boolean isSepStartOffset(int offset) {
            return sepStartOffsets.contains(offset);
        }

        private boolean isSepEndOffset(int offset) {
            return sepEndOffsets.contains(offset);
        }
    }
}