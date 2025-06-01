package org.es.tok.lucene;

import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class VocabTokenizer extends Tokenizer {
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);

    private final Trie trie;
    private String inputText;
    private Iterator<Emit> emitIterator;
    private boolean isInitialized = false;

    public VocabTokenizer(List<String> vocabulary, boolean caseSensitive) {
        Trie.TrieBuilder builder = Trie.builder();

        if (!caseSensitive) {
            builder.ignoreCase();
        }

        for (String word : vocabulary) {
            if (word != null && !word.trim().isEmpty()) {
                builder.addKeyword(word.trim());
            }
        }

        this.trie = builder.build();
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (!isInitialized) {
            initialize();
        }

        if (emitIterator != null && emitIterator.hasNext()) {
            Emit emit = emitIterator.next();
            clearAttributes();

            termAtt.copyBuffer(emit.getKeyword().toCharArray(), 0, emit.getKeyword().length());
            offsetAtt.setOffset(emit.getStart(), emit.getEnd() + 1);
            posIncrAtt.setPositionIncrement(1);

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
        Collection<Emit> emits = trie.parseText(inputText);
        emitIterator = emits.iterator();
        isInitialized = true;
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        isInitialized = false;
        emitIterator = null;
        inputText = null;
    }
}