package org.es.tok.core.model;

public class AnalyzeToken {
    private final String token;
    private final int startOffset;
    private final int endOffset;
    private final String type;
    private final String group;
    private final int position;

    public AnalyzeToken(String token, int startOffset, int endOffset, String type, String group, int position) {
        this.token = token;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.type = type;
        this.group = group;
        this.position = position;
    }

    public String getToken() {
        return token;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }

    public String getType() {
        return type;
    }

    public String getGroup() {
        return group;
    }

    public int getPosition() {
        return position;
    }
}