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

public class EsTokRelatedOwnersRequest extends BroadcastRequest<EsTokRelatedOwnersRequest> {
    public static final IndicesOptions DEFAULT_INDICES_OPTIONS = IndicesOptions.strictExpandOpenAndForbidClosed();

    private String text;
    private List<String> fields = Collections.emptyList();
    private int size = 10;
    private int scanLimit = 128;
    private int maxFields = 8;
    private boolean usePinyin = false;

    public EsTokRelatedOwnersRequest() {
        this(Strings.EMPTY_ARRAY);
    }

    public EsTokRelatedOwnersRequest(String... indices) {
        super(indices);
        indicesOptions(DEFAULT_INDICES_OPTIONS);
    }

    public EsTokRelatedOwnersRequest(StreamInput in) throws IOException {
        super(in);
        text = in.readString();
        fields = in.readStringCollectionAsList();
        size = in.readVInt();
        scanLimit = in.readVInt();
        maxFields = in.readVInt();
        usePinyin = in.readBoolean();
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = super.validate();
        if (text == null || text.isBlank()) {
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
        return validationException;
    }

    public String text() {
        return text;
    }

    public EsTokRelatedOwnersRequest text(String text) {
        this.text = text;
        return this;
    }

    public List<String> fields() {
        return fields;
    }

    public EsTokRelatedOwnersRequest fields(List<String> fields) {
        this.fields = fields == null ? Collections.emptyList() : List.copyOf(fields);
        return this;
    }

    public int size() {
        return size;
    }

    public EsTokRelatedOwnersRequest size(int size) {
        this.size = size;
        return this;
    }

    public int scanLimit() {
        return scanLimit;
    }

    public EsTokRelatedOwnersRequest scanLimit(int scanLimit) {
        this.scanLimit = scanLimit;
        return this;
    }

    public int maxFields() {
        return maxFields;
    }

    public EsTokRelatedOwnersRequest maxFields(int maxFields) {
        this.maxFields = maxFields;
        return this;
    }

    public boolean usePinyin() {
        return usePinyin;
    }

    public EsTokRelatedOwnersRequest usePinyin(boolean usePinyin) {
        this.usePinyin = usePinyin;
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

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(text);
        out.writeStringCollection(fields);
        out.writeVInt(size);
        out.writeVInt(scanLimit);
        out.writeVInt(maxFields);
        out.writeBoolean(usePinyin);
    }
}
