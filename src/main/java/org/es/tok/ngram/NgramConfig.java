package org.es.tok.ngram;

public class NgramConfig {
    private final boolean useNgram;
    private final boolean useBigram;
    private final boolean useVcgram;
    private final boolean useVbgram;

    public NgramConfig(boolean useNgram, boolean useBigram, boolean useVcgram, boolean useVbgram) {
        this.useNgram = useNgram;
        this.useBigram = useBigram;
        this.useVcgram = useVcgram;
        this.useVbgram = useVbgram;
    }

    public boolean isUseNgram() {
        return useNgram;
    }

    public boolean isUseBigram() {
        return useBigram;
    }

    public boolean isUseVcgram() {
        return useVcgram;
    }

    public boolean isUseVbgram() {
        return useVbgram;
    }

    public boolean hasAnyNgramEnabled() {
        return useNgram && (useBigram || useVcgram || useVbgram);
    }

    @Override
    public String toString() {
        return String.format("NgramConfig{useNgram=%s, useBigram=%s, useVcgram=%s, useVbgram=%s}",
                useNgram, useBigram, useVcgram, useVbgram);
    }
}