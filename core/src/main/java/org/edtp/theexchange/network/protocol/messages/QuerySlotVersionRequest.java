package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.InventoryAccess;

public class QuerySlotVersionRequest implements CorrelatedMessage {
    private String requestId;
    private int slot;
    private InventoryAccess access = InventoryAccess.server();

    public QuerySlotVersionRequest() {}

    public QuerySlotVersionRequest(int slot) {
        this(null, slot);
    }

    public QuerySlotVersionRequest(String requestId, int slot) {
        this(requestId, slot, InventoryAccess.server());
    }

    public QuerySlotVersionRequest(String requestId, int slot, InventoryAccess access) {
        this.requestId = requestId;
        this.slot = slot;
        this.access = access != null ? access : InventoryAccess.server();
    }

    @Override
    public String getRequestId() { return requestId; }

    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }

    public InventoryAccess getAccess() { return access != null ? access : InventoryAccess.server(); }
    public void setAccess(InventoryAccess access) { this.access = access != null ? access : InventoryAccess.server(); }
}
