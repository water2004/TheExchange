package org.edtp.theexchange.network.protocol.messages;

public class QuerySlotVersionResponse implements CorrelatedMessage {
    private String requestId;
    private int slot;
    private int version;

    public QuerySlotVersionResponse() {}

    public QuerySlotVersionResponse(int slot, int version) {
        this(null, slot, version);
    }

    public QuerySlotVersionResponse(String requestId, int slot, int version) {
        this.requestId = requestId;
        this.slot = slot;
        this.version = version;
    }

    @Override
    public String getRequestId() { return requestId; }

    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
