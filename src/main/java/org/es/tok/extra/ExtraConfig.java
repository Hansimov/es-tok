package org.es.tok.extra;

public class ExtraConfig {
    private final boolean ignoreCase;
    private final boolean dropDuplicates;

    public ExtraConfig(boolean ignoreCase, boolean dropDuplicates) {
        this.ignoreCase = ignoreCase;
        this.dropDuplicates = dropDuplicates;
    }

    public boolean isIgnoreCase() {
        return ignoreCase;
    }

    public boolean isDropDuplicates() {
        return dropDuplicates;
    }

    @Override
    public String toString() {
        return String.format("ExtraConfig{ignoreCase=%s, dropDuplicates=%s}", ignoreCase, dropDuplicates);
    }
}
