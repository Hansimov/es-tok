package org.es.tok.action;

import org.elasticsearch.action.ActionType;

public class EsTokSuggestAction extends ActionType<EsTokSuggestResponse> {
    public static final EsTokSuggestAction INSTANCE = new EsTokSuggestAction();
    public static final String NAME = "indices:data/read/es_tok/suggest";

    private EsTokSuggestAction() {
        super(NAME);
    }
}