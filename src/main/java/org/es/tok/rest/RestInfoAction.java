package org.es.tok.rest;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.common.Table;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.action.cat.AbstractCatAction;
import org.elasticsearch.rest.action.cat.RestTable;
import org.es.tok.EsTokPlugin;

import java.util.List;

import static org.elasticsearch.rest.RestRequest.Method.GET;

public class RestInfoAction extends AbstractCatAction {

    @Override
    public List<Route> routes() {
        return List.of(
                new Route(GET, "/_cat/es_tok"),
                new Route(GET, "/_cat/es_tok/version"));
    }

    @Override
    public String getName() {
        return "es_tok_info_action";
    }

    @Override
    protected RestChannelConsumer doCatRequest(final RestRequest request, final NodeClient client) {
        final String path = request.path();

        Table table = getTableWithHeader(request);
        table.startRow();

        if (path.endsWith("/version")) {
            table.addCell("es-tok");
            table.addCell(EsTokPlugin.VERSION);
            table.addCell("ES-TOK plugin");
        } else {
            table.addCell("es-tok");
            table.addCell("Ready");
            table.addCell("ES-TOK plugin");
        }

        table.endRow();
        return channel -> {
            try {
                channel.sendResponse(RestTable.buildResponse(table, channel));
            } catch (final Exception e) {
                channel.sendResponse(new RestResponse(channel, e));
            }
        };
    }

    @Override
    protected void documentation(StringBuilder sb) {
        sb.append("/_cat/es_tok\n");
        sb.append("/_cat/es_tok/version\n");
    }

    @Override
    protected Table getTableWithHeader(RestRequest request) {
        final Table table = new Table();
        table.startHeaders();
        table.addCell("plugin", "desc:plugin name");
        table.addCell("status", "desc:plugin status/version");
        table.addCell("description", "desc:plugin description");
        table.endHeaders();
        return table;
    }
}