package org.es.tok.tokenize;

import org.apache.lucene.util.Attribute;

public interface GroupAttribute extends Attribute {

    public static final String DEFAULT_GROUP = "default";

    public String group();

    public void setGroup(String group);

}