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

public class EsTokSuggestRequest extends BroadcastRequest<EsTokSuggestRequest> {
    public static final IndicesOptions DEFAULT_INDICES_OPTIONS = IndicesOptions.strictExpandOpenAndForbidClosed();

    private String text;
    private String mode = "prefix";
    private List<String> fields = Collections.emptyList();
    private int size = 5;
    private int scanLimit = 64;
    private int minPrefixLength = 1;
    private int minCandidateLength = 1;
    private boolean allowCompactBigrams = true;
    private boolean useCache = true;
    private int maxFields = 8;
    private int correctionRareDocFreq = 0;
    private int correctionMinLength = 4;
    private int correctionMaxEdits = 2;
    private int correctionPrefixLength = 1;
    private boolean usePinyin = false;
    private boolean prewarmPinyin = false;

    public EsTokSuggestRequest() {
        this(Strings.EMPTY_ARRAY);
    }

    public EsTokSuggestRequest(String... indices) {
        super(indices);
        indicesOptions(DEFAULT_INDICES_OPTIONS);
    }

    public EsTokSuggestRequest(StreamInput in) throws IOException {
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
        maxFields = in.readVInt();
        correctionRareDocFreq = in.readVInt();
        correctionMinLength = in.readVInt();
        correctionMaxEdits = in.readVInt();
        correctionPrefixLength = in.readVInt();
        usePinyin = in.readBoolean();
        prewarmPinyin = in.readBoolean();
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = super.validate();
        if ((text == null || text.isBlank()) && prewarmPinyin == false) {
            validationException = ValidateActions.addValidationError("text cannot be empty", validationException);
        }
        if (fields == null || fields.isEmpty()) {
            validationException = ValidateActions.addValidationError("fields cannot be empty", validationException);
        }
        if (fields != null && fields.size() > maxFields) {
            validationException = ValidateActions.addValidationError(
                    "fields size exceeds max_fields=" + maxFields,
                    validationException);
        }
        if (isSupportedMode(mode) == false) {
            validationException = ValidateActions.addValidationError(
                "mode must be 'prefix', 'associate', 'correction', 'auto' or 'semantic'",
                    validationException);
        }
        if (correctionMaxEdits < 1 || correctionMaxEdits > 2) {
            validationException = ValidateActions.addValidationError(
                "correction_max_edits must be 1 or 2",
                validationException);
        }
        return validationException;
    }

    public String text() {
        return text;
    }

    public EsTokSuggestRequest text(String text) {
        this.text = text;
        return this;
    }

    public String mode() {
        return mode;
    }

    public EsTokSuggestRequest mode(String mode) {
        this.mode = mode;
        return this;
    }

    public List<String> fields() {
        return fields;
    }

    public EsTokSuggestRequest fields(List<String> fields) {
        this.fields = fields == null ? Collections.emptyList() : List.copyOf(fields);
        return this;
    }

    public int size() {
        return size;
    }

    public EsTokSuggestRequest size(int size) {
        this.size = size;
        return this;
    }

    public int scanLimit() {
        return scanLimit;
    }

    public EsTokSuggestRequest scanLimit(int scanLimit) {
        this.scanLimit = scanLimit;
        return this;
    }

    public int minPrefixLength() {
        return minPrefixLength;
    }

    public EsTokSuggestRequest minPrefixLength(int minPrefixLength) {
        this.minPrefixLength = minPrefixLength;
        return this;
    }

    public int minCandidateLength() {
        return minCandidateLength;
    }

    public EsTokSuggestRequest minCandidateLength(int minCandidateLength) {
        this.minCandidateLength = minCandidateLength;
        return this;
    }

    public boolean allowCompactBigrams() {
        return allowCompactBigrams;
    }

    public EsTokSuggestRequest allowCompactBigrams(boolean allowCompactBigrams) {
        this.allowCompactBigrams = allowCompactBigrams;
        return this;
    }

    public boolean useCache() {
        return useCache;
    }

    public EsTokSuggestRequest useCache(boolean useCache) {
        this.useCache = useCache;
        return this;
    }

    public int maxFields() {
        return maxFields;
    }

    public EsTokSuggestRequest maxFields(int maxFields) {
        this.maxFields = maxFields;
        return this;
    }

    public int correctionRareDocFreq() {
        return correctionRareDocFreq;
    }

    public EsTokSuggestRequest correctionRareDocFreq(int correctionRareDocFreq) {
        this.correctionRareDocFreq = correctionRareDocFreq;
        return this;
    }

    public int correctionMinLength() {
        return correctionMinLength;
    }

    public EsTokSuggestRequest correctionMinLength(int correctionMinLength) {
        this.correctionMinLength = correctionMinLength;
        return this;
    }

    public int correctionMaxEdits() {
        return correctionMaxEdits;
    }

    public EsTokSuggestRequest correctionMaxEdits(int correctionMaxEdits) {
        this.correctionMaxEdits = correctionMaxEdits;
        return this;
    }

    public int correctionPrefixLength() {
        return correctionPrefixLength;
    }

    public EsTokSuggestRequest correctionPrefixLength(int correctionPrefixLength) {
        this.correctionPrefixLength = correctionPrefixLength;
        return this;
    }

    public boolean usePinyin() {
        return usePinyin;
    }

    public EsTokSuggestRequest usePinyin(boolean usePinyin) {
        this.usePinyin = usePinyin;
        return this;
    }

    public boolean prewarmPinyin() {
        return prewarmPinyin;
    }

    public EsTokSuggestRequest prewarmPinyin(boolean prewarmPinyin) {
        this.prewarmPinyin = prewarmPinyin;
        if (prewarmPinyin) {
            this.usePinyin = true;
        }
        return this;
    }

    public List<String> limitedFields() {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        if (fields.size() <= maxFields) {
            return fields;
        }
        return new ArrayList<>(fields.subList(0, maxFields));
    }

    private static boolean isSupportedMode(String mode) {
        return "prefix".equals(mode)
            || "associate".equals(mode)
                || "next_token".equals(mode)
                || "correction".equals(mode)
                || "auto".equals(mode)
                || "semantic".equals(mode);
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
        out.writeVInt(maxFields);
        out.writeVInt(correctionRareDocFreq);
        out.writeVInt(correctionMinLength);
        out.writeVInt(correctionMaxEdits);
        out.writeVInt(correctionPrefixLength);
        out.writeBoolean(usePinyin);
        out.writeBoolean(prewarmPinyin);
    }
}