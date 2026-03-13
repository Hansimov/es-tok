package org.es.tok.action;

import org.elasticsearch.action.ActionType;

public class EsTokRelatedOwnersAction extends ActionType<EsTokRelatedOwnersResponse> {
    public static final EsTokRelatedOwnersAction INSTANCE = new EsTokRelatedOwnersAction();
    public static final String NAME = "indices:data/read/es_tok/related_owners";

    private EsTokRelatedOwnersAction() {
        super(NAME);
    }
}
