package org.es.tok.action;

import org.elasticsearch.action.support.broadcast.BroadcastShardRequest;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ShardEsTokEntityRelationRequest extends BroadcastShardRequest {
    private final String relation;
    private final List<String> bvids;
    private final List<Long> mids;
    private final int size;
    private final int scanLimit;

    public ShardEsTokEntityRelationRequest(StreamInput in) throws IOException {
        super(in);
        relation = in.readString();
        bvids = in.readStringCollectionAsList();
        int midsSize = in.readVInt();
        List<Long> readMids = new ArrayList<>(midsSize);
        for (int index = 0; index < midsSize; index++) {
            readMids.add(in.readVLong());
        }
        mids = List.copyOf(readMids);
        size = in.readVInt();
        scanLimit = in.readVInt();
    }

    public ShardEsTokEntityRelationRequest(ShardId shardId, EsTokEntityRelationRequest request) {
        super(shardId, request);
        this.relation = request.relation();
        this.bvids = request.bvids();
        this.mids = request.mids();
        this.size = request.size();
        this.scanLimit = request.scanLimit();
    }

    public String relation() {
        return relation;
    }

    public List<String> bvids() {
        return bvids;
    }

    public List<Long> mids() {
        return mids;
    }

    public int size() {
        return size;
    }

    public int scanLimit() {
        return scanLimit;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(relation);
        out.writeStringCollection(bvids);
        out.writeVInt(mids.size());
        for (Long mid : mids) {
            out.writeVLong(mid);
        }
        out.writeVInt(size);
        out.writeVInt(scanLimit);
    }
}