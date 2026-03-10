package org.es.tok.action;

import org.elasticsearch.action.support.broadcast.BroadcastShardResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;
import java.util.List;

class ShardEsTokSuggestResponse extends BroadcastShardResponse {
    private final List<EsTokSuggestOption> options;
    private final boolean cacheHit;

    ShardEsTokSuggestResponse(StreamInput in) throws IOException {
        super(in);
        options = in.readCollectionAsList(EsTokSuggestOption::new);
        cacheHit = in.readBoolean();
    }

    ShardEsTokSuggestResponse(ShardId shardId, List<EsTokSuggestOption> options, boolean cacheHit) {
        super(shardId);
        this.options = List.copyOf(options);
        this.cacheHit = cacheHit;
    }

    List<EsTokSuggestOption> options() {
        return options;
    }

    boolean cacheHit() {
        return cacheHit;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeCollection(options);
        out.writeBoolean(cacheHit);
    }
}