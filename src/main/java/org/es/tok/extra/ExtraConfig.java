package org.es.tok.extra;

public class ExtraConfig {
    private final boolean ignoreCase;
    private final boolean ignoreHant;
    private final boolean dropDuplicates;

    public ExtraConfig(boolean ignoreCase, boolean ignoreHant, boolean dropDuplicates) {
        this.ignoreCase = ignoreCase;
        this.ignoreHant = ignoreHant;
        this.dropDuplicates = dropDuplicates;
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

    @Override
    public String toString() {
        return String.format("ExtraConfig{ignoreCase=%s, ignoreHant=%s, dropDuplicates=%s}",
                ignoreCase, ignoreHant, dropDuplicates);
    }
}
