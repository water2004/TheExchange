package org.edtp.theexchange.network.protocol.messages;

public class QuerySlotStateRequest {
    private int slot;

    public QuerySlotStateRequest() {}

    public QuerySlotStateRequest(int slot) {
        this.slot = slot;
    }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }
}
