package org.es.tok.action;

import org.elasticsearch.action.support.broadcast.BroadcastShardResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;
import java.util.List;

class ShardEsTokEntityRelationResponse extends BroadcastShardResponse {
    private final List<EsTokRelatedVideoOption> videos;
    private final List<EsTokRelatedOwnerOption> owners;

    ShardEsTokEntityRelationResponse(StreamInput in) throws IOException {
        super(in);
        videos = in.readCollectionAsList(EsTokRelatedVideoOption::new);
        owners = in.readCollectionAsList(EsTokRelatedOwnerOption::new);
    }

    ShardEsTokEntityRelationResponse(ShardId shardId, List<EsTokRelatedVideoOption> videos, List<EsTokRelatedOwnerOption> owners) {
        super(shardId);
        this.videos = List.copyOf(videos);
        this.owners = List.copyOf(owners);
    }

    List<EsTokRelatedVideoOption> videos() {
        return videos;
    }

    List<EsTokRelatedOwnerOption> owners() {
        return owners;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeCollection(videos);
        out.writeCollection(owners);
    }
}