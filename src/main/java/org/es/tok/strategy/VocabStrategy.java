package org.es.tok.strategy;

import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class VocabStrategy implements TokenStrategy {
    private final Trie trie;

    public VocabStrategy(List<String> vocabs, boolean ignoreCase) {
        if (vocabs == null || vocabs.isEmpty()) {
            this.trie = null;
            return;
        }

        Trie.TrieBuilder builder = Trie.builder();
        if (ignoreCase) {
            builder.ignoreCase();
        }

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
            tokens.add(new TokenInfo(
                    emit.getKeyword(),
                    emit.getStart(),
                    emit.getEnd() + 1,
                    "vocab",
                    position++));
        }

        return tokens;
    }
}