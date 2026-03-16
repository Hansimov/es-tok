package org.es.tok.action;

import org.elasticsearch.action.support.DefaultShardOperationFailedException;
import org.elasticsearch.action.support.broadcast.BaseBroadcastResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class EsTokEntityRelationResponse extends BaseBroadcastResponse implements ToXContentObject {
    private final String relation;
    private final List<String> bvids;
    private final List<Long> mids;
    private final List<EsTokRelatedVideoOption> videos;
    private final List<EsTokRelatedOwnerOption> owners;

    public EsTokEntityRelationResponse(StreamInput in) throws IOException {
        super(in);
        relation = in.readString();
        bvids = in.readStringCollectionAsList();
        int midsSize = in.readVInt();
        List<Long> readMids = new ArrayList<>(midsSize);
        for (int index = 0; index < midsSize; index++) {
            readMids.add(in.readVLong());
        }
        mids = List.copyOf(readMids);
        videos = in.readCollectionAsList(EsTokRelatedVideoOption::new);
        owners = in.readCollectionAsList(EsTokRelatedOwnerOption::new);
    }

    public EsTokEntityRelationResponse(
            String relation,
            List<String> bvids,
            List<Long> mids,
            List<EsTokRelatedVideoOption> videos,
            List<EsTokRelatedOwnerOption> owners,
            int totalShards,
            int successfulShards,
            int failedShards,
            List<DefaultShardOperationFailedException> shardFailures) {
        super(totalShards, successfulShards, failedShards, shardFailures);
        this.relation = relation;
        this.bvids = List.copyOf(bvids);
        this.mids = List.copyOf(mids);
        this.videos = List.copyOf(videos);
        this.owners = List.copyOf(owners);
    }

    public List<EsTokRelatedVideoOption> videos() {
        return videos;
    }

    public List<EsTokRelatedOwnerOption> owners() {
        return owners;
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
        out.writeCollection(videos);
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
        builder.field("relation", relation);
        if (!bvids.isEmpty()) {
            builder.field("bvids", bvids);
        }
        if (!mids.isEmpty()) {
            builder.field("mids", mids);
        }
        if (relation.startsWith("related_videos_")) {
            builder.startArray("videos");
            for (EsTokRelatedVideoOption video : videos) {
                video.toXContent(builder, params);
            }
            builder.endArray();
        } else {
            builder.startArray("owners");
            for (EsTokRelatedOwnerOption owner : owners) {
                owner.toXContent(builder, params);
            }
            builder.endArray();
        }
        builder.endObject();
        return builder;
    }
}