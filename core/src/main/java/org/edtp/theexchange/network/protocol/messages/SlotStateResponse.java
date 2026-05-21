package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.NeutralItem;

public class SlotStateResponse {
    private int slot;
    private NeutralItem item;
    private int version;

    public SlotStateResponse() {}

    public SlotStateResponse(int slot, NeutralItem item, int version) {
        this.slot = slot;
        this.item = item;
        this.version = version;
    }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }

    public NeutralItem getItem() { return item; }
    public void setItem(NeutralItem item) { this.item = item; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
