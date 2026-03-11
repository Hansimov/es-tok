package org.es.tok.action;

import org.elasticsearch.action.support.broadcast.BroadcastShardRequest;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;
import java.util.List;

public class ShardEsTokSuggestRequest extends BroadcastShardRequest {
    private final String text;
    private final String mode;
    private final List<String> fields;
    private final int size;
    private final int scanLimit;
    private final int minPrefixLength;
    private final int minCandidateLength;
    private final boolean allowCompactBigrams;
    private final boolean useCache;
    private final int correctionRareDocFreq;
    private final int correctionMinLength;
    private final int correctionMaxEdits;
    private final int correctionPrefixLength;
    private final boolean usePinyin;

    public ShardEsTokSuggestRequest(StreamInput in) throws IOException {
        super(in);
        text = in.readString();
        mode = in.readString();
        fields = in.readStringCollectionAsList();
        size = in.readVInt();
        scanLimit = in.readVInt();
        minPrefixLength = in.readVInt();
        minCandidateLength = in.readVInt();
        allowCompactBigrams = in.readBoolean();
        useCache = in.readBoolean();
        correctionRareDocFreq = in.readVInt();
        correctionMinLength = in.readVInt();
        correctionMaxEdits = in.readVInt();
        correctionPrefixLength = in.readVInt();
        usePinyin = in.readBoolean();
    }

    public ShardEsTokSuggestRequest(ShardId shardId, EsTokSuggestRequest request) {
        super(shardId, request);
        this.text = request.text();
        this.mode = request.mode();
        this.fields = request.limitedFields();
        this.size = request.size();
        this.scanLimit = request.scanLimit();
        this.minPrefixLength = request.minPrefixLength();
        this.minCandidateLength = request.minCandidateLength();
        this.allowCompactBigrams = request.allowCompactBigrams();
        this.useCache = request.useCache();
        this.correctionRareDocFreq = request.correctionRareDocFreq();
        this.correctionMinLength = request.correctionMinLength();
        this.correctionMaxEdits = request.correctionMaxEdits();
        this.correctionPrefixLength = request.correctionPrefixLength();
        this.usePinyin = request.usePinyin();
    }

    public String text() {
        return text;
    }

    public String mode() {
        return mode;
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

    public int minPrefixLength() {
        return minPrefixLength;
    }

    public int minCandidateLength() {
        return minCandidateLength;
    }

    public boolean allowCompactBigrams() {
        return allowCompactBigrams;
    }

    public boolean useCache() {
        return useCache;
    }

    public int correctionRareDocFreq() {
        return correctionRareDocFreq;
    }

    public int correctionMinLength() {
        return correctionMinLength;
    }

    public int correctionMaxEdits() {
        return correctionMaxEdits;
    }

    public int correctionPrefixLength() {
        return correctionPrefixLength;
    }

    public boolean usePinyin() {
        return usePinyin;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(text);
        out.writeString(mode);
        out.writeStringCollection(fields);
        out.writeVInt(size);
        out.writeVInt(scanLimit);
        out.writeVInt(minPrefixLength);
        out.writeVInt(minCandidateLength);
        out.writeBoolean(allowCompactBigrams);
        out.writeBoolean(useCache);
        out.writeVInt(correctionRareDocFreq);
        out.writeVInt(correctionMinLength);
        out.writeVInt(correctionMaxEdits);
        out.writeVInt(correctionPrefixLength);
        out.writeBoolean(usePinyin);
    }
}