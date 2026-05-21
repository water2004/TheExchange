package org.edtp.theexchange.network.protocol.messages;

public class QuerySlotVersionRequest {
    private int slot;

    public QuerySlotVersionRequest() {}

    public QuerySlotVersionRequest(int slot) {
        this.slot = slot;
    }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }
}
