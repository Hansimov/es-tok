package org.es.tok.strategy;

import org.es.tok.ngram.NgramConfig;
import org.es.tok.ngram.NgramTextBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class NgramStrategy {
    private final NgramConfig ngramConfig;

    private static final Set<String> CATEG_TYPES = new HashSet<>(Arrays.asList(
            "arab", "eng", "cjk", "lang"));
    private static final Set<String> VOCAB_TYPES = new HashSet<>(Arrays.asList(
            "vocab"));
    private static final Set<String> WORD_TYPES = new HashSet<>(Arrays.asList(
            "arab", "eng", "cjk", "lang", "vocab"));
    private static final Set<String> SEP_TYPES = new HashSet<>(Arrays.asList(
            "dash", "ws", "mask"));
    private static final Set<String> NORD_TYPES = new HashSet<>(Arrays.asList(
            "nord"));

    public NgramStrategy(NgramConfig ngramConfig) {
        this.ngramConfig = ngramConfig;
    }

    private boolean isCategToken(TokenStrategy.TokenInfo token) {
        return CATEG_TYPES.contains(token.getType());
    }

    private boolean isVocabToken(TokenStrategy.TokenInfo token) {
        return VOCAB_TYPES.contains(token.getType());
    }

    private boolean isWordToken(TokenStrategy.TokenInfo token) {
        return WORD_TYPES.contains(token.getType());
    }

    private boolean isSepToken(TokenStrategy.TokenInfo token) {
        return SEP_TYPES.contains(token.getType());
    }

    private boolean isNordToken(TokenStrategy.TokenInfo token) {
        return NORD_TYPES.contains(token.getType());
    }

    private boolean isContainedIn(TokenStrategy.TokenInfo token1, TokenStrategy.TokenInfo token2) {
        // token1 is contained in token2, i.e., L2<=L1<=R1<=R2
        return token2.getStartOffset() <= token1.getStartOffset() &&
                token1.getEndOffset() <= token2.getEndOffset();
    }

    private boolean isOneContainsOther(TokenStrategy.TokenInfo token1, TokenStrategy.TokenInfo token2) {
        return isContainedIn(token1, token2) || isContainedIn(token2, token1);
    }

    private boolean isNonOverlapAfter(TokenStrategy.TokenInfo secondToken, TokenStrategy.TokenInfo firstToken) {
        return secondToken.getStartOffset() >= firstToken.getEndOffset();
    }

    private boolean isOverlapOrAdjacent(TokenStrategy.TokenInfo secondToken, TokenStrategy.TokenInfo firstToken) {
        return secondToken.getStartOffset() <= firstToken.getEndOffset();
    }

    // main function
    public List<TokenStrategy.TokenInfo> generateNgrams(List<TokenStrategy.TokenInfo> baseTokens) {
        List<TokenStrategy.TokenInfo> ngramTokens = new ArrayList<>();
        if (!ngramConfig.hasAnyNgramEnabled()) {
            return ngramTokens;
        }
        if (ngramConfig.isUseBigram()) {
            ngramTokens.addAll(generateBigrams(baseTokens));
        }
        if (ngramConfig.isUseVbgram()) {
            ngramTokens.addAll(generateVbgrams(baseTokens));
        }
        if (ngramConfig.isUseVcgram()) {
            ngramTokens.addAll(generateVcgrams(baseTokens));
        }
        return ngramTokens;
    }

    // process ngrams that could have spaces
    public List<TokenStrategy.TokenInfo> spacifyNgramText(
            NgramTextBuilder ngramTextBuilder, String ngramType, boolean hasSpace) {
        List<TokenStrategy.TokenInfo> ngramTokens = new ArrayList<>();
        String ngramString = ngramTextBuilder.toString();
        int startOffset = ngramTextBuilder.getStartOffset();
        int endOffset = ngramTextBuilder.getEndOffset();

        if (hasSpace) {
            // replace multiple spaces with a single space
            String ngramStringClean = ngramString.replaceAll("\\s+", " ");
            ngramTokens.add(new TokenStrategy.TokenInfo(
                    ngramStringClean,
                    startOffset,
                    endOffset,
                    ngramType,
                    0,
                    "ngram"));
            // remove spaces
            String ngramStringNoSpace = ngramString.replaceAll(" ", "");
            ngramTokens.add(new TokenStrategy.TokenInfo(
                    ngramStringNoSpace,
                    startOffset,
                    endOffset,
                    ngramType,
                    0,
                    "ngram"));
        } else {
            ngramTokens.add(new TokenStrategy.TokenInfo(
                    ngramString,
                    startOffset,
                    endOffset,
                    ngramType,
                    0,
                    "ngram"));
        }
        return ngramTokens;
    }

    private List<TokenStrategy.TokenInfo> findAllValidNextAdjacentTokens(
            TokenStrategy.TokenInfo firstToken,
            List<TokenStrategy.TokenInfo> baseTokens,
            int firstTokenIdx,
            Predicate<TokenStrategy.TokenInfo> secondTokenPredicate) {

        List<TokenStrategy.TokenInfo> validTokens = new ArrayList<>();

        // upperbound of candicate tokens idx:
        // break at first "nord" or nonSecondTokenPredicate token after firstToken
        int candidateIdxUB = baseTokens.size();
        for (int i = firstTokenIdx + 1; i < baseTokens.size(); i++) {
            TokenStrategy.TokenInfo token = baseTokens.get(i);
            if (isNonOverlapAfter(token, firstToken) &&
                    !isSepToken(token) &&
                    (isNordToken(token) || !secondTokenPredicate.test(token))) {
                candidateIdxUB = i;
                break;
            }
        }

        // upperbound of candicate tokens start_offset:
        // break at first nonSep and secondTokenPredicate-passed token after firstToken
        // (implicit constraint: idx < candidateIdxUB)
        int candidateStartOffsetUB = baseTokens.get(candidateIdxUB - 1).getStartOffset();
        for (int i = firstTokenIdx + 1; i < candidateIdxUB; i++) {
            TokenStrategy.TokenInfo token = baseTokens.get(i);
            if (isNonOverlapAfter(token, firstToken) &&
                    !isSepToken(token) &&
                    secondTokenPredicate.test(token)) {
                candidateStartOffsetUB = token.getStartOffset() + 1;
                break;
            }
        }

        // i: index of candidate token
        for (int i = firstTokenIdx + 1; i < candidateIdxUB; i++) {
            TokenStrategy.TokenInfo candidateToken = baseTokens.get(i);
            // Skip if one token is contained in the other
            if (isOneContainsOther(firstToken, candidateToken)) {
                continue;
            }
            // Skip if candidate token does not pass the second token predicate
            if (!secondTokenPredicate.test(candidateToken)) {
                continue;
            }
            if (isOverlapOrAdjacent(candidateToken, firstToken)) {
                // valid if candidate token is overlapping or adjacent to first token
                validTokens.add(candidateToken);
            } else {
                if (candidateToken.getStartOffset() < candidateStartOffsetUB) {
                    // valid for any candidate token that starts before the upper bound
                    validTokens.add(candidateToken);
                } else {
                    break;
                }
            }
        }

        return validTokens;
    }

    private List<TokenStrategy.TokenInfo> generateNgramsGeneric(
            List<TokenStrategy.TokenInfo> tokens,
            Predicate<TokenStrategy.TokenInfo> firstTokenPredicate,
            Predicate<TokenStrategy.TokenInfo> secondTokenPredicate,
            Predicate<TokenPair> extraPredicate,
            String ngramType) {

        List<TokenStrategy.TokenInfo> ngrams = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            TokenStrategy.TokenInfo firstToken = tokens.get(i);
            if (!firstTokenPredicate.test(firstToken)) {
                continue;
            }

            // Find all valid adjacent tokens for this first token
            List<TokenStrategy.TokenInfo> validSecondTokens = findAllValidNextAdjacentTokens(
                    firstToken, tokens, i, secondTokenPredicate);

            for (TokenStrategy.TokenInfo secondToken : validSecondTokens) {
                if (extraPredicate != null &&
                        !extraPredicate.test(new TokenPair(firstToken, secondToken))) {
                    continue;
                }

                NgramTextBuilder ngramTextBuilder = new NgramTextBuilder(firstToken);

                // Add space token if there is gap between two tokens
                boolean hasSpace = false;
                if (firstToken.getEndOffset() < secondToken.getStartOffset()) {
                    hasSpace = true;
                    TokenStrategy.TokenInfo spaceToken = new TokenStrategy.TokenInfo(
                            " ",
                            firstToken.getEndOffset(),
                            secondToken.getStartOffset(),
                            "space",
                            0,
                            "ngram");
                    ngramTextBuilder.appendToken(spaceToken);
                }

                ngramTextBuilder.appendToken(secondToken);

                ngrams.addAll(spacifyNgramText(ngramTextBuilder, ngramType, hasSpace));
            }
        }
        return ngrams;
    }

    // Helper class to hold a pair of tokens
    private static class TokenPair {
        private final TokenStrategy.TokenInfo first;
        private final TokenStrategy.TokenInfo second;

        public TokenPair(TokenStrategy.TokenInfo first, TokenStrategy.TokenInfo second) {
            this.first = first;
            this.second = second;
        }

        public TokenStrategy.TokenInfo getFirst() {
            return first;
        }

        public TokenStrategy.TokenInfo getSecond() {
            return second;
        }
    }

    // bigram: ngrams that concat exactly 2 adjacent categ tokens
    private List<TokenStrategy.TokenInfo> generateBigrams(List<TokenStrategy.TokenInfo> tokens) {
        return generateNgramsGeneric(
                tokens,
                this::isCategToken, // first token must be categ
                this::isCategToken, // second token must be categ
                null, // no additional validation
                "bigram");
    }

    // vbgram: ngrams that concat exactly 2 adjacent vocab tokens
    private List<TokenStrategy.TokenInfo> generateVbgrams(List<TokenStrategy.TokenInfo> tokens) {
        return generateNgramsGeneric(
                tokens,
                this::isVocabToken, // first token must be vocab
                this::isVocabToken, // second token must be vocab
                null, // no additional validation
                "vbgram");
    }

    // vcgram: ngrams that concat exactly 2 adjacent word tokens, but must contain
    // at least one vocab token
    private List<TokenStrategy.TokenInfo> generateVcgrams(List<TokenStrategy.TokenInfo> tokens) {
        return generateNgramsGeneric(
                tokens,
                this::isWordToken, // first token must be word
                this::isWordToken, // second token must be word
                pair -> isVocabToken(pair.getFirst()) || isVocabToken(pair.getSecond()), // at least one vocab
                "vcgram");
    }
}