package org.es.tok.ngram;

import org.es.tok.strategy.TokenStrategy;

public class NgramTextBuilder {
    private final StringBuilder stringBuilder;
    private int currentStartOffset;
    private int currentEndOffset;

    public NgramTextBuilder() {
        this.stringBuilder = new StringBuilder();
        this.currentStartOffset = -1;
        this.currentEndOffset = -1;
    }

    public NgramTextBuilder(TokenStrategy.TokenInfo initToken) {
        this.stringBuilder = new StringBuilder(initToken.getText());
        this.currentStartOffset = initToken.getStartOffset();
        this.currentEndOffset = initToken.getEndOffset();
    }

    public NgramTextBuilder appendToken(TokenStrategy.TokenInfo token) {
        String tokenText = token.getText();
        int newStartOffset = token.getStartOffset();
        int newEndOffset = token.getEndOffset();

        int overlapLength = currentEndOffset - newStartOffset;
        int newTokenSubstringStart = Math.max(0, overlapLength);
        String appendText = tokenText.substring(newTokenSubstringStart);
        this.stringBuilder.append(appendText);

        if (currentStartOffset == -1) {
            this.currentStartOffset = newStartOffset;
        }
        this.currentEndOffset = newEndOffset;

        return this;
    }

    public int getStartOffset() {
        return currentStartOffset;
    }

    public int getEndOffset() {
        return currentEndOffset;
    }

    public int length() {
        return stringBuilder.length();
    }

    @Override
    public String toString() {
        return stringBuilder.toString();
    }

    public StringBuilder getStringBuilder() {
        return stringBuilder;
    }
}