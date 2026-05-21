package org.edtp.theexchange.network.protocol.messages;

public class QuerySlotVersionRequest implements CorrelatedMessage {
    private String requestId;
    private int slot;

    public QuerySlotVersionRequest() {}

    public QuerySlotVersionRequest(int slot) {
        this(null, slot);
    }

    public QuerySlotVersionRequest(String requestId, int slot) {
        this.requestId = requestId;
        this.slot = slot;
    }

    @Override
    public String getRequestId() { return requestId; }

    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }
}
