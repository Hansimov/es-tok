package org.es.tok.action;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.xcontent.ToXContentFragment;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;

public record EsTokRelatedVideoOption(
        String bvid,
        String title,
        long ownerMid,
        String ownerName,
        int docFreq,
        float score,
        int shardCount) implements Writeable, ToXContentFragment {

    public EsTokRelatedVideoOption(StreamInput in) throws IOException {
        this(in.readString(), in.readString(), in.readVLong(), in.readString(), in.readVInt(), in.readFloat(), in.readVInt());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(bvid);
        out.writeString(title);
        out.writeVLong(ownerMid);
        out.writeString(ownerName);
        out.writeVInt(docFreq);
        out.writeFloat(score);
        out.writeVInt(shardCount);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("bvid", bvid);
        builder.field("title", title);
        builder.field("owner_mid", ownerMid);
        builder.field("owner_name", ownerName);
        builder.field("doc_freq", docFreq);
        builder.field("score", score);
        builder.field("shard_count", shardCount);
        builder.endObject();
        return builder;
    }
}