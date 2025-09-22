package org.es.tok.tokenize;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.es.tok.config.EsTokConfig;
import org.es.tok.extra.HantToHansConverter;
import org.es.tok.strategy.CategStrategy;
import org.es.tok.strategy.NgramStrategy;
import org.es.tok.strategy.TokenStrategy;
import org.es.tok.strategy.VocabStrategy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class EsTokTokenizer extends Tokenizer {
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
    private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class);
    private final GroupAttribute groupAtt = addAttribute(GroupAttribute.class);

    private final EsTokConfig config;
    private final VocabStrategy vocabStrategy;
    private final CategStrategy categStrategy;
    private final NgramStrategy ngramStrategy;
    private final HantToHansConverter hantToHansConverter;

    private String inputText;
    private Iterator<TokenStrategy.TokenInfo> tokenIterator;
    private boolean isInitialized = false;

    // New constructor using EsTokConfig
    public EsTokTokenizer(EsTokConfig config) {
        this.config = config;

        if (!config.getVocabConfig().isUseVocab() && !config.getCategConfig().isUseCateg()) {
            throw new IllegalArgumentException("Must use at least one strategy: use_vocab, use_categ");
        }

        this.vocabStrategy = config.getVocabConfig().isUseVocab()
                ? new VocabStrategy(config.getVocabConfig().getVocabs())
                : null;
        this.categStrategy = config.getCategConfig().isUseCateg()
                ? new CategStrategy(config.getCategConfig().isSplitWord())
                : null;
        this.ngramStrategy = config.getNgramConfig().hasAnyNgramEnabled() ? new NgramStrategy(config.getNgramConfig())
                : null;

        // Initialize HantToHansConverter if needed
        if (config.getExtraConfig().isIgnoreHant()) {
            try {
                this.hantToHansConverter = HantToHansConverter.getInstance();
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize HantToHansConverter", e);
            }
        } else {
            this.hantToHansConverter = null;
        }
    }

    // Performance-optimized constructor using pre-built strategies
    public EsTokTokenizer(EsTokConfig config, VocabStrategy vocabStrategy, CategStrategy categStrategy,
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

    @Override
    public boolean incrementToken() throws IOException {
        if (!isInitialized) {
            initialize();
        }

        if (tokenIterator != null && tokenIterator.hasNext()) {
            TokenStrategy.TokenInfo token = tokenIterator.next();
            clearAttributes();

            termAtt.copyBuffer(token.getText().toCharArray(), 0, token.getText().length());
            offsetAtt.setOffset(token.getStartOffset(), token.getEndOffset());
            posIncrAtt.setPositionIncrement(1);
            typeAtt.setType(token.getType());
            groupAtt.setGroup(token.getGroup());

            return true;
        }

        return false;
    }

    private void initialize() throws IOException {
        if (isInitialized) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[8192];
        int numChars;

        while ((numChars = input.read(buffer)) != -1) {
            sb.append(buffer, 0, numChars);
        }

        inputText = sb.toString();
        List<TokenStrategy.TokenInfo> allTokens = processText(inputText);
        tokenIterator = allTokens.iterator();
        isInitialized = true;
    }

    // Main processing function
    private List<TokenStrategy.TokenInfo> processText(String text) {
        // apply ignore_case
        if (config.getExtraConfig().isIgnoreCase()) {
            text = text.toLowerCase();
        }

        // apply hant-to-hans conversion
        if (config.getExtraConfig().isIgnoreHant() && hantToHansConverter != null) {
            text = hantToHansConverter.convert(text);
        }

        // generate base tokens from categ and vocab strategies
        List<TokenStrategy.TokenInfo> baseTokens = generateBaseTokens(text);

        // drop categ tokens covered by multiple vocab tokens
        if (config.getExtraConfig().isDropCategs()) {
            baseTokens = dropCategTokens(baseTokens, 2);
        }

        // deduplicate base tokens
        if (config.getExtraConfig().isDropDuplicates()) {
            baseTokens = dropDuplicatedTokens(baseTokens);
        }

        // sort base tokens
        baseTokens = sortTokensByOffset(baseTokens);

        // generate n-grams
        List<TokenStrategy.TokenInfo> allTokens = new ArrayList<>(baseTokens);
        if (ngramStrategy != null) {
            List<TokenStrategy.TokenInfo> ngramTokens = ngramStrategy.generateNgrams(baseTokens);
            allTokens.addAll(ngramTokens);
        }

        // deduplicate all tokens
        if (config.getExtraConfig().isDropDuplicates()) {
            allTokens = dropDuplicatedTokens(allTokens);
        }

        // sort all tokens to ensure proper offset ordering
        allTokens = sortTokensByOffset(allTokens);

        return allTokens;
    }

    // Generate base tokens from categ and vocab strategies
    private List<TokenStrategy.TokenInfo> generateBaseTokens(String text) {
        List<TokenStrategy.TokenInfo> baseTokens = new ArrayList<>();

        // Generate tokens from categ strategy
        if (config.getCategConfig().isUseCateg() && categStrategy != null) {
            baseTokens.addAll(categStrategy.tokenize(text));
        }

        // Generate tokens from vocab strategy
        if (config.getVocabConfig().isUseVocab() && vocabStrategy != null) {
            List<TokenStrategy.TokenInfo> vocabTokens = vocabStrategy.tokenize(text);
            baseTokens.addAll(vocabTokens);
        }

        return baseTokens;
    }

    // Sort tokens by start_offset and end_offset, finally by token text
    private List<TokenStrategy.TokenInfo> sortTokensByOffset(List<TokenStrategy.TokenInfo> tokens) {
        List<TokenStrategy.TokenInfo> sortedTokens = new ArrayList<>(tokens);
        sortedTokens.sort((a, b) -> {
            // 1st, compare by start offset
            int offsetCompare = Integer.compare(a.getStartOffset(), b.getStartOffset());
            if (offsetCompare != 0) {
                return offsetCompare;
            }

            // 2nd, compare by end offset
            int endOffsetCompare = Integer.compare(a.getEndOffset(), b.getEndOffset());
            if (endOffsetCompare != 0) {
                return endOffsetCompare;
            }

            // Finally, compare by token text for consistent ordering
            return a.getText().compareTo(b.getText());
        });

        return sortedTokens;
    }

    // Remove duplicated tokens based on: token text, start_offset, and end_offset
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

    // Drop categ tokens covered by multiple vocab tokens
    private List<TokenStrategy.TokenInfo> dropCategTokens(List<TokenStrategy.TokenInfo> tokens,
            int vocabFreqThreshold) {
        // sort tokens by offset for efficient looping later
        List<TokenStrategy.TokenInfo> sortedTokens = sortTokensByOffset(tokens);
        // use iterator to remove elements efficiently
        Iterator<TokenStrategy.TokenInfo> iterator = tokens.iterator();
        while (iterator.hasNext()) {
            TokenStrategy.TokenInfo token = iterator.next();
            // only affect categ tokens
            if (!isTokenGroup(token, "categ")) {
                continue;
            }

            int vocabFreq = 0;
            int categStart = token.getStartOffset();
            // find start position for vocab token to check
            int loopStart = findFirstTokenAtOrBefore(sortedTokens, categStart);
            // count vocab tokens that cover this categ token
            for (int i = loopStart; i < sortedTokens.size(); i++) {
                TokenStrategy.TokenInfo vToken = sortedTokens.get(i);
                // if exceed offset, break
                if (vToken.getStartOffset() > categStart) {
                    break;
                }
                // find matched vocab token
                if (isTokenGroup(vToken, "vocab") && isContainedIn(token, vToken)) {
                    vocabFreq++;
                    if (vocabFreq >= vocabFreqThreshold) {
                        break;
                    }
                }
            }
            // remove categ token if satisfy requirements
            if (vocabFreq >= vocabFreqThreshold) {
                iterator.remove();
            }
        }

        return tokens;
    }

    // find min token idx with start_offset <= target_offset
    private int findFirstTokenAtOrBefore(List<TokenStrategy.TokenInfo> sortedTokens, int targetStartOffset) {
        int left = 0;
        int right = sortedTokens.size() - 1;
        int resIdx = 0;
        // get rightmost token whose startOffset <= targetStartOffset
        while (left <= right) {
            int mid = left + (right - left) / 2;
            if (sortedTokens.get(mid).getStartOffset() <= targetStartOffset) {
                resIdx = mid;
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }
        // get leftmost token whose endOffset >= targetStartOffset
        while (resIdx > 0 && sortedTokens.get(resIdx - 1).getEndOffset() >= targetStartOffset) {
            resIdx--;
        }
        return resIdx;
    }

    // check if token belongs to give group
    private boolean isTokenGroup(TokenStrategy.TokenInfo token, String group) {
        return group.equals(token.getGroup());
    }

    // check if token1 is contained in token2 (L2<=L1<=R1<=R2)
    private boolean isContainedIn(TokenStrategy.TokenInfo token1, TokenStrategy.TokenInfo token2) {
        return token2.getStartOffset() <= token1.getStartOffset() &&
                token1.getEndOffset() <= token2.getEndOffset();
    }

    // unique token by: (text, start_offset, end_offset)
    private static class TokenKey {
        private final String text;
        private final int startOffset;
        private final int endOffset;

        public TokenKey(String text, int startOffset, int endOffset) {
            this.text = text;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            TokenKey tokenKey = (TokenKey) o;
            return startOffset == tokenKey.startOffset &&
                    endOffset == tokenKey.endOffset &&
                    text.equals(tokenKey.text);
        }

        @Override
        public int hashCode() {
            int result = text.hashCode();
            result = 31 * result + startOffset;
            result = 31 * result + endOffset;
            return result;
        }

        @Override
        public String toString() {
            return String.format("TokenKey{text='%s', startOffset=%d, endOffset=%d}",
                    text, startOffset, endOffset);
        }
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        isInitialized = false;
        tokenIterator = null;
        inputText = null;
    }
}