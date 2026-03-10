package org.es.tok.categ;

public class CategConfig {
    private final boolean useCateg;
    private final boolean splitWord;

    public CategConfig(boolean useCateg, boolean splitWord) {
        this.useCateg = useCateg;
        this.splitWord = splitWord;
    }

    public boolean isUseCateg() {
        return useCateg;
    }

    public boolean isSplitWord() {
        return splitWord;
    }

    @Override
    public String toString() {
        return String.format("CategConfig{useCateg=%s, splitWord=%s}", useCateg, splitWord);
    }
}