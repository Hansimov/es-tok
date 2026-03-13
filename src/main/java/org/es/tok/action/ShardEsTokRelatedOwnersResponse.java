package org.es.tok.action;

import org.elasticsearch.action.support.broadcast.BroadcastShardResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;
import java.util.List;

class ShardEsTokRelatedOwnersResponse extends BroadcastShardResponse {
    private final List<EsTokRelatedOwnerOption> owners;

    ShardEsTokRelatedOwnersResponse(StreamInput in) throws IOException {
        super(in);
        owners = in.readCollectionAsList(EsTokRelatedOwnerOption::new);
    }

    ShardEsTokRelatedOwnersResponse(ShardId shardId, List<EsTokRelatedOwnerOption> owners) {
        super(shardId);
        this.owners = List.copyOf(owners);
    }

    List<EsTokRelatedOwnerOption> owners() {
        return owners;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeCollection(owners);
    }
}
