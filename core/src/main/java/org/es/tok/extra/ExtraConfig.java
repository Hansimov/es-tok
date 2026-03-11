package org.es.tok.extra;

public class ExtraConfig {
    private final boolean ignoreCase;
    private final boolean ignoreHant;
    private final boolean dropDuplicates;
    private final boolean dropCategs;
    private final boolean dropVocabs;
    private final boolean emitPinyinTerms;

    public ExtraConfig(boolean ignoreCase, boolean ignoreHant, boolean dropDuplicates, boolean dropCategs,
            boolean dropVocabs, boolean emitPinyinTerms) {
        this.ignoreCase = ignoreCase;
        this.ignoreHant = ignoreHant;
        this.dropDuplicates = dropDuplicates;
        this.dropCategs = dropCategs;
        this.dropVocabs = dropVocabs;
        this.emitPinyinTerms = emitPinyinTerms;
    }

    public boolean isIgnoreCase() {
        return ignoreCase;
    }

    public boolean isIgnoreHant() {
        return ignoreHant;
    }

    public boolean isDropDuplicates() {
        return dropDuplicates;
    }

    public boolean isDropCategs() {
        return dropCategs;
    }

    public boolean isDropVocabs() {
        return dropVocabs;
    }

    public boolean isEmitPinyinTerms() {
        return emitPinyinTerms;
    }

    @Override
    public String toString() {
        return String.format(
                "ExtraConfig{ignoreCase=%s, ignoreHant=%s, dropDuplicates=%s, dropCategs=%s, dropVocabs=%s, emitPinyinTerms=%s}",
                ignoreCase, ignoreHant, dropDuplicates, dropCategs, dropVocabs, emitPinyinTerms);
    }
}
