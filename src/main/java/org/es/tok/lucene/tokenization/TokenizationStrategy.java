package org.es.tok.lucene.tokenization;

import java.util.List;

public interface TokenizationStrategy {
    List<TokenInfo> tokenize(String text);
    
    public static class TokenInfo {
        private final String text;
        private final int startOffset;
        private final int endOffset;
        private final String type;
        private final int position;

        public TokenInfo(String text, int startOffset, int endOffset, String type, int position) {
            this.text = text;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.type = type;
            this.position = position;
        }

        // Getters
        public String getText() { return text; }
        public int getStartOffset() { return startOffset; }
        public int getEndOffset() { return endOffset; }
        public String getType() { return type; }
        public int getPosition() { return position; }
    }
}