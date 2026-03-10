package org.es.tok.action;

import org.elasticsearch.action.support.DefaultShardOperationFailedException;
import org.elasticsearch.action.support.broadcast.BaseBroadcastResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.List;

public class EsTokSuggestResponse extends BaseBroadcastResponse implements ToXContentObject {
    private final String text;
    private final String mode;
    private final List<String> fields;
    private final List<EsTokSuggestOption> options;
    private final int cacheHitCount;

    public EsTokSuggestResponse(StreamInput in) throws IOException {
        super(in);
        text = in.readString();
        mode = in.readString();
        fields = in.readStringCollectionAsList();
        options = in.readCollectionAsList(EsTokSuggestOption::new);
        cacheHitCount = in.readVInt();
    }

    public EsTokSuggestResponse(
            String text,
            String mode,
            List<String> fields,
            List<EsTokSuggestOption> options,
            int cacheHitCount,
            int totalShards,
            int successfulShards,
            int failedShards,
            List<DefaultShardOperationFailedException> shardFailures) {
        super(totalShards, successfulShards, failedShards, shardFailures);
        this.text = text;
        this.mode = mode;
        this.fields = List.copyOf(fields);
        this.options = List.copyOf(options);
        this.cacheHitCount = cacheHitCount;
    }

    public List<EsTokSuggestOption> options() {
        return options;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(text);
        out.writeString(mode);
        out.writeStringCollection(fields);
        out.writeCollection(options);
        out.writeVInt(cacheHitCount);
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
        builder.field("mode", mode);
        builder.field("fields", fields);
        builder.field("cache_hit_count", cacheHitCount);
        builder.startArray("options");
        for (EsTokSuggestOption option : options) {
            option.toXContent(builder, params);
        }
        builder.endArray();
        builder.endObject();
        return builder;
    }
}