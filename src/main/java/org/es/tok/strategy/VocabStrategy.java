package org.es.tok.strategy;

import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class VocabStrategy implements TokenStrategy {
    private final Trie trie;

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

            tokens.add(new TokenInfo(
                    tokenText,
                    startOffset,
                    endOffset,
                    "vocab",
                    position++,
                    "vocab"));

            if (shouldConcat(tokenText)) {
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

    private boolean shouldConcat(String tokenText) {
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
}