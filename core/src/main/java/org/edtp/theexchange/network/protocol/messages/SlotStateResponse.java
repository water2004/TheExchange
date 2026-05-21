package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.NeutralItem;

public class SlotStateResponse implements CorrelatedMessage {
    private String requestId;
    private int slot;
    private NeutralItem item;
    private int version;

    public SlotStateResponse() {}

    public SlotStateResponse(int slot, NeutralItem item, int version) {
        this(null, slot, item, version);
    }

    public SlotStateResponse(String requestId, int slot, NeutralItem item, int version) {
        this.requestId = requestId;
        this.slot = slot;
        this.item = item;
        this.version = version;
    }

    @Override
    public String getRequestId() { return requestId; }

    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }

    public NeutralItem getItem() { return item; }
    public void setItem(NeutralItem item) { this.item = item; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
