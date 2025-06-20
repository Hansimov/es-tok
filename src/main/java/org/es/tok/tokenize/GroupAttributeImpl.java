package org.es.tok.tokenize;

import org.apache.lucene.util.AttributeImpl;
import org.apache.lucene.util.AttributeReflector;

/** Default implementation of {@link GroupAttribute}. */
public class GroupAttributeImpl extends AttributeImpl implements GroupAttribute {
    private String group = DEFAULT_GROUP;

    /** Initialize this attribute with {@link #DEFAULT_GROUP} */
    public GroupAttributeImpl() {
    }

    /** Initialize this attribute with given group */
    public GroupAttributeImpl(String group) {
        this.group = group;
    }

    @Override
    public String group() {
        return group;
    }

    @Override
    public void setGroup(String group) {
        this.group = group;
    }

    @Override
    public void clear() {
        group = DEFAULT_GROUP;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GroupAttribute && group.equals(((GroupAttribute) other).group());
    }

    @Override
    public int hashCode() {
        return group.hashCode();
    }

    @Override
    public void copyTo(AttributeImpl target) {
        ((GroupAttribute) target).setGroup(group);
    }

    @Override
    public void reflectWith(AttributeReflector reflector) {
        reflector.reflect(GroupAttribute.class, "group", group);
    }
}