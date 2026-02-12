package org.es.tok.strategy;

import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class VocabStrategy implements TokenStrategy {
    // Global cache: all indexes and REST share the same Trie for the same vocab
    // list.
    // Building an Aho-Corasick Trie from millions of words costs 1-4GB memory;
    // without this cache, each index or REST request would build its own copy →
    // OOM.
    private static final ConcurrentHashMap<String, VocabStrategy> globalCache = new ConcurrentHashMap<>();

    private final Trie trie;

    /**
     * Get or create a cached VocabStrategy for the given vocab list.
     * Thread-safe: uses ConcurrentHashMap.computeIfAbsent for atomic
     * check-and-create.
     * Returns null if vocabs is null or empty.
     */
    public static VocabStrategy getOrCreate(List<String> vocabs) {
        if (vocabs == null || vocabs.isEmpty()) {
            return null;
        }
        String key = vocabs.size() + ":" + vocabs.hashCode();
        return globalCache.computeIfAbsent(key, k -> new VocabStrategy(vocabs));
    }

    public VocabStrategy(List<String> vocabs) {
        if (vocabs == null || vocabs.isEmpty()) {
            this.trie = null;
            return;
        }

        Trie.TrieBuilder builder = Trie.builder();
        for (String word : vocabs) {
            if (word != null && !word.trim().isEmpty()) {
                builder.addKeyword(word.trim());
            }
        }

        this.trie = builder.build();
    }

    @Override
    public List<TokenInfo> tokenize(String text) {
        List<TokenInfo> tokens = new ArrayList<>();

        if (trie == null) {
            return tokens;
        }

        Collection<Emit> emits = trie.parseText(text);
        int position = 0;

        for (Emit emit : emits) {
            int startOffset = emit.getStart();
            int endOffset = emit.getEnd() + 1;
            String tokenText = emit.getKeyword();

            if (shouldDropVocabToken(tokenText, text, startOffset, endOffset)) {
                continue;
            }

            tokens.add(new TokenInfo(
                    tokenText,
                    startOffset,
                    endOffset,
                    "vocab",
                    position++,
                    "vocab"));

            if (!shouldConcatVocabToken(tokenText)) {
                continue;
            }

            String concatToken = concatToken(tokenText);
            if (concatToken != null) {
                tokens.add(new TokenInfo(
                        concatToken,
                        startOffset,
                        endOffset,
                        "vocab_concat",
                        position++,
                        "vocab"));
            }
        }

        return tokens;
    }

    private String concatToken(String tokenText) {
        if (tokenText == null || tokenText.isEmpty()) {
            return null;
        }

        StringBuilder builder = new StringBuilder(tokenText.length());
        boolean removed = false;

        for (int i = 0; i < tokenText.length(); i++) {
            char c = tokenText.charAt(i);
            if (isRemovableSeparator(c)) {
                removed = true;
                continue;
            }
            builder.append(c);
        }

        if (!removed || builder.length() == 0) {
            return null;
        }

        return builder.toString();
    }

    private boolean shouldConcatVocabToken(String tokenText) {
        if (tokenText == null || tokenText.isEmpty()) {
            return false;
        }

        for (int i = 0; i < tokenText.length(); i++) {
            if (isRemovableSeparator(tokenText.charAt(i))) {
                return true;
            }
        }

        return false;
    }

    private boolean isRemovableSeparator(char c) {
        return Character.isWhitespace(c) || isDash(c) || isMask(c);
    }

    private boolean isDash(char c) {
        return c == '-' || c == '+' || c == '_' || c == '.';
    }

    private boolean isMask(char c) {
        return c == '▂';
    }

    private boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private boolean isNum(char c) {
        return (c >= '0' && c <= '9');
    }

    private boolean isAlphaNum(char c) {
        return isAlpha(c) || isNum(c);
    }

    // drop vocab token if both bounds are same type of alpha-num
    private boolean shouldDropVocabToken(String tokenText, String text, int startOffset, int endOffset) {
        // only check alpha-num-sep tokens
        if (!isConsistOfAlphaNumSep(tokenText)) {
            return false;
        }

        boolean isStartSame = false;
        boolean isStartBothNum = false;
        if (startOffset > 0) {
            char charBefore = text.charAt(startOffset - 1);
            char charStart = text.charAt(startOffset);
            isStartBothNum = bothNum(charBefore, charStart);
            isStartSame = isSameTypeOfAlphaNum(charBefore, charStart);
        }

        boolean isEndSame = false;
        boolean isEndBothNum = false;
        if (endOffset < text.length()) {
            char charEnd = text.charAt(endOffset - 1);
            char charAfter = text.charAt(endOffset);
            isEndBothNum = bothNum(charEnd, charAfter);
            isEndSame = isSameTypeOfAlphaNum(charEnd, charAfter);
        }

        // drop if either bound bothNum is true,
        // as number should not be truncated in most cases
        if (isStartBothNum || isEndBothNum) {
            return true;
        }

        // drop if either bound of short tokens is same type of alpha-num,
        // otherwise there would be too many false positives (unmeaningful segments)
        if (tokenText.length() < 3) {
            return isStartSame || isEndSame;
        }

        return isStartSame && isEndSame;
    }

    private boolean isConsistOfAlphaNumSep(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (!isAlphaNum(c) && !isRemovableSeparator(c)) {
                return false;
            }
        }
        return true;
    }

    private boolean bothNum(char c1, char c2) {
        return isNum(c1) && isNum(c2);
    }

    private boolean isSameTypeOfAlphaNum(char c1, char c2) {
        return (isNum(c1) && isNum(c2)) || (isAlpha(c1) && isAlpha(c2));
    }
}