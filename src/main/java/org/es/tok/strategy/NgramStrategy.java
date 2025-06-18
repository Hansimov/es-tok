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

    // bigram: ngrams that concat adjacent categ tokens
    private List<TokenStrategy.TokenInfo> generateBigrams(List<TokenStrategy.TokenInfo> tokens) {
        List<TokenStrategy.TokenInfo> bigrams = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            TokenStrategy.TokenInfo thisToken = tokens.get(i);
            if (!isCategToken(thisToken)) {
                continue;
            }
            StringBuilder ngramText = new StringBuilder(thisToken.getText());
            int startOffset = thisToken.getStartOffset();
            int endOffset = thisToken.getEndOffset();
            boolean hasSpace = false;
            for (int j = i + 1; j < tokens.size(); j++) {
                TokenStrategy.TokenInfo nextToken = tokens.get(j);
                if (isSepToken(nextToken)) {
                    ngramText.append(" ");
                    hasSpace = true;
                    continue;
                } else if (isCategToken(nextToken)) {
                    ngramText.append(nextToken.getText());
                    endOffset = nextToken.getEndOffset();
                    bigrams.addAll(processNgramText(ngramText, startOffset, endOffset, "bigram", hasSpace));
                } else {
                    break;
                }
            }
        }
        return bigrams;
    }

    // vbgram: ngrams that concat adjacent vocab tokens
    private List<TokenStrategy.TokenInfo> generateVbgrams(List<TokenStrategy.TokenInfo> tokens) {
        List<TokenStrategy.TokenInfo> vbgrams = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            TokenStrategy.TokenInfo thisToken = tokens.get(i);
            if (!isVocabToken(thisToken)) {
                continue;
            }
            StringBuilder ngramText = new StringBuilder(thisToken.getText());
            int startOffset = thisToken.getStartOffset();
            int endOffset = thisToken.getEndOffset();
            boolean hasSpace = false;
            for (int j = i + 1; j < tokens.size(); j++) {
                TokenStrategy.TokenInfo nextToken = tokens.get(j);
                if (isSepToken(nextToken)) {
                    ngramText.append(" ");
                    hasSpace = true;
                    continue;
                } else if (isVocabToken(nextToken)) {
                    ngramText.append(nextToken.getText());
                    endOffset = nextToken.getEndOffset();
                    vbgrams.addAll(processNgramText(ngramText, startOffset, endOffset, "vbgram", hasSpace));
                } else {
                    break;
                }
            }
        }
        return vbgrams;
    }

    // vcgram: ngrams that concat adjacent tokens, but must contain vocab token
    private List<TokenStrategy.TokenInfo> generateVcgrams(List<TokenStrategy.TokenInfo> tokens) {
        List<TokenStrategy.TokenInfo> vcgrams = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            TokenStrategy.TokenInfo thisToken = tokens.get(i);
            if (!isWordToken(thisToken)) {
                continue;
            }
            StringBuilder ngramText = new StringBuilder(thisToken.getText());
            int startOffset = thisToken.getStartOffset();
            int endOffset = thisToken.getEndOffset();
            boolean hasVocab = isVocabToken(thisToken);
            boolean hasSpace = false;
            for (int j = i + 1; j < tokens.size(); j++) {
                TokenStrategy.TokenInfo nextToken = tokens.get(j);
                if (isSepToken(nextToken)) {
                    ngramText.append(" ");
                    hasSpace = true;
                    continue;
                } else if (isWordToken(nextToken)) {
                    ngramText.append(nextToken.getText());
                    endOffset = nextToken.getEndOffset();
                    if (isVocabToken(nextToken)) {
                        hasVocab = true;
                    }
                    if (hasVocab) {
                        vcgrams.addAll(processNgramText(ngramText, startOffset, endOffset, "vcgram", hasSpace));
                    } else {
                        break;
                    }
                } else {
                    break;

                }
            }
        }
        return vcgrams;
    }
}