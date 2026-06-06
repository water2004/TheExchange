package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.InventoryScope;

public class SlotStateResponse implements CorrelatedMessage {
    private String requestId;
    private int slot;
    private NeutralItem item;
    private int version;
    private InventoryScope scope = InventoryScope.server();
    private boolean success = true;
    private String failReason;

    public SlotStateResponse() {}

    public SlotStateResponse(int slot, NeutralItem item, int version) {
        this(null, slot, item, version);
    }

    public SlotStateResponse(String requestId, int slot, NeutralItem item, int version) {
        this(requestId, slot, item, version, InventoryScope.server());
    }

    public SlotStateResponse(String requestId, int slot, NeutralItem item, int version, InventoryScope scope) {
        this.requestId = requestId;
        this.slot = slot;
        this.item = item;
        this.version = version;
        this.scope = scope != null ? scope : InventoryScope.server();
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

    public InventoryScope getScope() { return scope != null ? scope : InventoryScope.server(); }
    public void setScope(InventoryScope scope) { this.scope = scope != null ? scope : InventoryScope.server(); }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
}
