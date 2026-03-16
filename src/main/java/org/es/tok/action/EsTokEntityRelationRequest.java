package org.es.tok.action;

import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.ValidateActions;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.broadcast.BroadcastRequest;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EsTokEntityRelationRequest extends BroadcastRequest<EsTokEntityRelationRequest> {
    public static final IndicesOptions DEFAULT_INDICES_OPTIONS = IndicesOptions.strictExpandOpenAndForbidClosed();
    public static final int MAX_SEEDS = 32;

    public static final String RELATED_VIDEOS_BY_VIDEOS = "related_videos_by_videos";
    public static final String RELATED_OWNERS_BY_VIDEOS = "related_owners_by_videos";
    public static final String RELATED_VIDEOS_BY_OWNERS = "related_videos_by_owners";
    public static final String RELATED_OWNERS_BY_OWNERS = "related_owners_by_owners";

    private String relation;
    private List<String> bvids = Collections.emptyList();
    private List<Long> mids = Collections.emptyList();
    private int size = 10;
    private int scanLimit = 128;

    public EsTokEntityRelationRequest() {
        this(Strings.EMPTY_ARRAY);
    }

    public EsTokEntityRelationRequest(String... indices) {
        super(indices);
        indicesOptions(DEFAULT_INDICES_OPTIONS);
    }

    public EsTokEntityRelationRequest(StreamInput in) throws IOException {
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

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = super.validate();
        if (!isSupportedRelation(relation)) {
            validationException = ValidateActions.addValidationError(
                    "relation must be one of related_videos_by_videos, related_owners_by_videos, related_videos_by_owners, related_owners_by_owners",
                    validationException);
        }
        if (sourceIsVideos()) {
            if (bvids == null || bvids.isEmpty()) {
                validationException = ValidateActions.addValidationError("bvids cannot be empty", validationException);
            }
            if (bvids != null && bvids.size() > MAX_SEEDS) {
                validationException = ValidateActions.addValidationError("bvids size exceeds max seeds=" + MAX_SEEDS, validationException);
            }
        }
        if (sourceIsOwners()) {
            if (mids == null || mids.isEmpty()) {
                validationException = ValidateActions.addValidationError("mids cannot be empty", validationException);
            }
            if (mids != null && mids.size() > MAX_SEEDS) {
                validationException = ValidateActions.addValidationError("mids size exceeds max seeds=" + MAX_SEEDS, validationException);
            }
        }
        return validationException;
    }

    public String relation() {
        return relation;
    }

    public EsTokEntityRelationRequest relation(String relation) {
        this.relation = relation;
        return this;
    }

    public List<String> bvids() {
        return bvids;
    }

    public EsTokEntityRelationRequest bvids(List<String> bvids) {
        this.bvids = bvids == null ? Collections.emptyList() : List.copyOf(bvids);
        return this;
    }

    public List<Long> mids() {
        return mids;
    }

    public EsTokEntityRelationRequest mids(List<Long> mids) {
        this.mids = mids == null ? Collections.emptyList() : List.copyOf(mids);
        return this;
    }

    public int size() {
        return size;
    }

    public EsTokEntityRelationRequest size(int size) {
        this.size = size;
        return this;
    }

    public int scanLimit() {
        return scanLimit;
    }

    public EsTokEntityRelationRequest scanLimit(int scanLimit) {
        this.scanLimit = scanLimit;
        return this;
    }

    public boolean sourceIsVideos() {
        return RELATED_VIDEOS_BY_VIDEOS.equals(relation) || RELATED_OWNERS_BY_VIDEOS.equals(relation);
    }

    public boolean sourceIsOwners() {
        return RELATED_VIDEOS_BY_OWNERS.equals(relation) || RELATED_OWNERS_BY_OWNERS.equals(relation);
    }

    public boolean targetIsVideos() {
        return RELATED_VIDEOS_BY_VIDEOS.equals(relation) || RELATED_VIDEOS_BY_OWNERS.equals(relation);
    }

    public boolean targetIsOwners() {
        return RELATED_OWNERS_BY_VIDEOS.equals(relation) || RELATED_OWNERS_BY_OWNERS.equals(relation);
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

    public static boolean isSupportedRelation(String relation) {
        return RELATED_VIDEOS_BY_VIDEOS.equals(relation)
                || RELATED_OWNERS_BY_VIDEOS.equals(relation)
                || RELATED_VIDEOS_BY_OWNERS.equals(relation)
                || RELATED_OWNERS_BY_OWNERS.equals(relation);
    }
}