package org.edtp.theexchange.network.protocol.messages;

public class QuerySlotVersionResponse {
    private int slot;
    private int version;

    public QuerySlotVersionResponse() {}

    public QuerySlotVersionResponse(int slot, int version) {
        this.slot = slot;
        this.version = version;
    }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
