package org.es.tok.action;

import org.elasticsearch.action.support.DefaultShardOperationFailedException;
import org.elasticsearch.action.support.broadcast.BaseBroadcastResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.List;

public class EsTokRelatedOwnersResponse extends BaseBroadcastResponse implements ToXContentObject {
    private final String text;
    private final List<String> fields;
    private final List<EsTokRelatedOwnerOption> owners;

    public EsTokRelatedOwnersResponse(StreamInput in) throws IOException {
        super(in);
        text = in.readString();
        fields = in.readStringCollectionAsList();
        owners = in.readCollectionAsList(EsTokRelatedOwnerOption::new);
    }

    public EsTokRelatedOwnersResponse(
            String text,
            List<String> fields,
            List<EsTokRelatedOwnerOption> owners,
            int totalShards,
            int successfulShards,
            int failedShards,
            List<DefaultShardOperationFailedException> shardFailures) {
        super(totalShards, successfulShards, failedShards, shardFailures);
        this.text = text;
        this.fields = List.copyOf(fields);
        this.owners = List.copyOf(owners);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(text);
        out.writeStringCollection(fields);
        out.writeCollection(owners);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.startObject("_shards");
        builder.field("total", getTotalShards());
        builder.field("successful", getSuccessfulShards());
        builder.field("failed", getFailedShards());
        builder.endObject();
        builder.field("text", text);
        builder.field("fields", fields);
        builder.startArray("owners");
        for (EsTokRelatedOwnerOption owner : owners) {
            owner.toXContent(builder, params);
        }
        builder.endArray();
        builder.endObject();
        return builder;
    }
}
