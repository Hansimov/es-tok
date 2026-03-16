package org.es.tok.action;

import org.elasticsearch.action.ActionType;

public class EsTokEntityRelationsAction extends ActionType<EsTokEntityRelationResponse> {
    public static final EsTokEntityRelationsAction INSTANCE = new EsTokEntityRelationsAction();
    public static final String NAME = "indices:data/read/es_tok/entity_relations";

    private EsTokEntityRelationsAction() {
        super(NAME);
    }
}