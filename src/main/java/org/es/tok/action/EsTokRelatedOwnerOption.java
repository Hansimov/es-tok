package org.es.tok.action;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.xcontent.ToXContentFragment;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;

public record EsTokRelatedOwnerOption(
        long mid,
        String name,
        int docFreq,
        float score,
        int shardCount) implements Writeable, ToXContentFragment {

    public EsTokRelatedOwnerOption(StreamInput in) throws IOException {
        this(in.readVLong(), in.readString(), in.readVInt(), in.readFloat(), in.readVInt());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVLong(mid);
        out.writeString(name);
        out.writeVInt(docFreq);
        out.writeFloat(score);
        out.writeVInt(shardCount);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("mid", mid);
        builder.field("name", name);
        builder.field("doc_freq", docFreq);
        builder.field("score", score);
        builder.field("shard_count", shardCount);
        builder.endObject();
        return builder;
    }
}
