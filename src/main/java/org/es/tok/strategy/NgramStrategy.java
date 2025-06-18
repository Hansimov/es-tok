package org.es.tok.strategy;

import org.es.tok.ngram.NgramConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NgramStrategy {
    private final NgramConfig ngramConfig;

    private static final Set<String> CATEG_TYPES = new HashSet<>(Arrays.asList(
            "arab", "eng", "cjk", "lang"));
    private static final Set<String> VOCAB_TYPES = new HashSet<>(Arrays.asList(
            "vocab"));
    private static final Set<String> WORD_TYPES = new HashSet<>(Arrays.asList(
            "arab", "eng", "cjk", "lang", "vocab"));
    private static final Set<String> SEP_TYPES = new HashSet<>(Arrays.asList(
            "dash", "ws", "mask", "nord"));

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

    private boolean isContainedIn(TokenStrategy.TokenInfo token1, TokenStrategy.TokenInfo token2) {
        // token1 is contained in token2, i.e., L2<=L1<=R1<=R2
        return token2.getStartOffset() <= token1.getStartOffset() &&
                token1.getEndOffset() <= token2.getEndOffset();
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
    public List<TokenStrategy.TokenInfo> processNgramText(
            StringBuilder ngramText, int startOffset, int endOffset, String ngramType, boolean hasSpace) {
        List<TokenStrategy.TokenInfo> ngramTokens = new ArrayList<>();
        String ngramString = ngramText.toString();
        if (hasSpace) {
            // replace multiple spaces with a single space
            String ngramStringClean = ngramString.replaceAll("\\s+", " ");
            ngramTokens.add(new TokenStrategy.TokenInfo(
                    ngramStringClean,
                    startOffset,
                    endOffset,
                    ngramType,
                    0));
            // remove spaces
            String ngramStringNoSpace = ngramString.replaceAll(" ", "");
            ngramTokens.add(new TokenStrategy.TokenInfo(
                    ngramStringNoSpace,
                    startOffset,
                    endOffset,
                    ngramType,
                    0));
        } else {
            ngramTokens.add(new TokenStrategy.TokenInfo(
                    ngramString,
                    startOffset,
                    endOffset,
                    ngramType,
                    0));
        }
        return ngramTokens;
    }

    // bigram: ngrams that concat exactly 2 adjacent categ tokens
    private List<TokenStrategy.TokenInfo> generateBigrams(List<TokenStrategy.TokenInfo> tokens) {
        List<TokenStrategy.TokenInfo> bigrams = new ArrayList<>();

        for (int i = 0; i < tokens.size() - 1; i++) {
            TokenStrategy.TokenInfo firstToken = tokens.get(i);
            if (!isCategToken(firstToken)) {
                continue;
            }

            // Look for the next categ token that doesn't overlap with current token
            for (int j = i + 1; j < tokens.size(); j++) {
                TokenStrategy.TokenInfo secondToken = tokens.get(j);

                // Skip if current token is contained in or overlaps with the second token
                if (isContainedIn(firstToken, secondToken) || isContainedIn(secondToken, firstToken)) {
                    continue;
                }

                if (isSepToken(secondToken)) {
                    continue; // Skip separators, keep looking
                } else if (isCategToken(secondToken)) {
                    // Found valid second categ token, create bigram
                    StringBuilder ngramText = new StringBuilder();
                    ngramText.append(firstToken.getText());

                    boolean hasSpace = false;
                    // Check if there are separators between the tokens
                    if (firstToken.getEndOffset() < secondToken.getStartOffset()) {
                        ngramText.append(" ");
                        hasSpace = true;
                    }

                    ngramText.append(secondToken.getText());

                    bigrams.addAll(processNgramText(
                            ngramText,
                            firstToken.getStartOffset(),
                            secondToken.getEndOffset(),
                            "bigram",
                            hasSpace));
                    break; // Only create one bigram per starting token
                } else {
                    break; // Stop if we encounter non-categ, non-sep token
                }
            }
        }
        return bigrams;
    }

    // vbgram: ngrams that concat exactly 2 adjacent vocab tokens
    private List<TokenStrategy.TokenInfo> generateVbgrams(List<TokenStrategy.TokenInfo> tokens) {
        List<TokenStrategy.TokenInfo> vbgrams = new ArrayList<>();

        for (int i = 0; i < tokens.size() - 1; i++) {
            TokenStrategy.TokenInfo firstToken = tokens.get(i);
            if (!isVocabToken(firstToken)) {
                continue;
            }

            // Look for the next vocab token that doesn't overlap with current token
            for (int j = i + 1; j < tokens.size(); j++) {
                TokenStrategy.TokenInfo secondToken = tokens.get(j);

                // Skip if current token is contained in or overlaps with the second token
                if (isContainedIn(firstToken, secondToken) || isContainedIn(secondToken, firstToken)) {
                    continue;
                }

                if (isSepToken(secondToken)) {
                    continue; // Skip separators, keep looking
                } else if (isVocabToken(secondToken)) {
                    // Found valid second vocab token, create vbgram
                    StringBuilder ngramText = new StringBuilder();
                    ngramText.append(firstToken.getText());

                    boolean hasSpace = false;
                    // Check if there are separators between the tokens
                    if (firstToken.getEndOffset() < secondToken.getStartOffset()) {
                        ngramText.append(" ");
                        hasSpace = true;
                    }

                    ngramText.append(secondToken.getText());

                    vbgrams.addAll(processNgramText(
                            ngramText,
                            firstToken.getStartOffset(),
                            secondToken.getEndOffset(),
                            "vbgram",
                            hasSpace));
                    break; // Only create one vbgram per starting token
                } else {
                    break; // Stop if we encounter non-vocab, non-sep token
                }
            }
        }
        return vbgrams;
    }

    // vcgram: ngrams that concat exactly 2 adjacent word tokens, but must contain
    // at least one vocab token
    private List<TokenStrategy.TokenInfo> generateVcgrams(List<TokenStrategy.TokenInfo> tokens) {
        List<TokenStrategy.TokenInfo> vcgrams = new ArrayList<>();

        for (int i = 0; i < tokens.size() - 1; i++) {
            TokenStrategy.TokenInfo firstToken = tokens.get(i);
            if (!isWordToken(firstToken)) {
                continue;
            }

            // Look for the next word token that doesn't overlap with current token
            for (int j = i + 1; j < tokens.size(); j++) {
                TokenStrategy.TokenInfo secondToken = tokens.get(j);

                // Skip if current token is contained in or overlaps with the second token
                if (isContainedIn(firstToken, secondToken) || isContainedIn(secondToken, firstToken)) {
                    continue;
                }

                if (isSepToken(secondToken)) {
                    continue; // Skip separators, keep looking
                } else if (isWordToken(secondToken)) {
                    // Check if at least one token is a vocab token
                    boolean hasVocab = isVocabToken(firstToken) || isVocabToken(secondToken);
                    if (hasVocab) {
                        // Create vcgram
                        StringBuilder ngramText = new StringBuilder();
                        ngramText.append(firstToken.getText());

                        boolean hasSpace = false;
                        // Check if there are separators between the tokens
                        if (firstToken.getEndOffset() < secondToken.getStartOffset()) {
                            ngramText.append(" ");
                            hasSpace = true;
                        }

                        ngramText.append(secondToken.getText());

                        vcgrams.addAll(processNgramText(
                                ngramText,
                                firstToken.getStartOffset(),
                                secondToken.getEndOffset(),
                                "vcgram",
                                hasSpace));
                    }
                    break; // Only create one vcgram per starting token
                } else {
                    break; // Stop if we encounter non-word, non-sep token
                }
            }
        }
        return vcgrams;
    }
}