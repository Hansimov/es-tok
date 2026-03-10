package org.es.tok.action;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.xcontent.ToXContentFragment;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;

public record EsTokSuggestOption(
        String text,
        int docFreq,
        float score,
        String type,
        int shardCount) implements Writeable, ToXContentFragment {

    public EsTokSuggestOption(StreamInput in) throws IOException {
        this(in.readString(), in.readVInt(), in.readFloat(), in.readString(), in.readVInt());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(text);
        out.writeVInt(docFreq);
        out.writeFloat(score);
        out.writeString(type);
        out.writeVInt(shardCount);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("text", text);
        builder.field("doc_freq", docFreq);
        builder.field("score", score);
        builder.field("type", type);
        builder.field("shard_count", shardCount);
        builder.endObject();
        return builder;
    }
}