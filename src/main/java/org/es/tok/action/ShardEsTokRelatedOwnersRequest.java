package org.es.tok.action;

import org.elasticsearch.action.support.broadcast.BroadcastShardRequest;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;
import java.util.List;

public class ShardEsTokRelatedOwnersRequest extends BroadcastShardRequest {
    private final String text;
    private final List<String> fields;
    private final int size;
    private final int scanLimit;
    private final boolean usePinyin;

    public ShardEsTokRelatedOwnersRequest(StreamInput in) throws IOException {
        super(in);
        text = in.readString();
        fields = in.readStringCollectionAsList();
        size = in.readVInt();
        scanLimit = in.readVInt();
        usePinyin = in.readBoolean();
    }

    public ShardEsTokRelatedOwnersRequest(ShardId shardId, EsTokRelatedOwnersRequest request) {
        super(shardId, request);
        this.text = request.text();
        this.fields = request.limitedFields();
        this.size = request.size();
        this.scanLimit = request.scanLimit();
        this.usePinyin = request.usePinyin();
    }

    public String text() {
        return text;
    }

    public List<String> fields() {
        return fields;
    }

    public int size() {
        return size;
    }

    public int scanLimit() {
        return scanLimit;
    }

    public boolean usePinyin() {
        return usePinyin;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(text);
        out.writeStringCollection(fields);
        out.writeVInt(size);
        out.writeVInt(scanLimit);
        out.writeBoolean(usePinyin);
    }
}
