package org.es.tok.extra;

public class ExtraConfig {
    private final boolean ignoreCase;
    private final boolean ignoreHant;
    private final boolean dropDuplicates;
    private final boolean dropCategs;

    public ExtraConfig(boolean ignoreCase, boolean ignoreHant, boolean dropDuplicates, boolean dropCategs) {
        this.ignoreCase = ignoreCase;
        this.ignoreHant = ignoreHant;
        this.dropDuplicates = dropDuplicates;
        this.dropCategs = dropCategs;
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

    @Override
    public String toString() {
        return String.format("ExtraConfig{ignoreCase=%s, ignoreHant=%s, dropDuplicates=%s, dropCategs=%s}",
                ignoreCase, ignoreHant, dropDuplicates, dropCategs);
    }
}
