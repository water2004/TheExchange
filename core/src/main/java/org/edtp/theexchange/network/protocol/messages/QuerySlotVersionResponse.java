package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.InventoryScope;

public class QuerySlotVersionResponse implements CorrelatedMessage {
    private String requestId;
    private int slot;
    private int version;
    private InventoryScope scope = InventoryScope.server();
    private boolean success = true;
    private String failReason;

    public QuerySlotVersionResponse() {}

    public QuerySlotVersionResponse(int slot, int version) {
        this(null, slot, version);
    }

    public QuerySlotVersionResponse(String requestId, int slot, int version) {
        this(requestId, slot, version, InventoryScope.server());
    }

    public QuerySlotVersionResponse(String requestId, int slot, int version, InventoryScope scope) {
        this.requestId = requestId;
        this.slot = slot;
        this.version = version;
        this.scope = scope != null ? scope : InventoryScope.server();
    }

    @Override
    public String getRequestId() { return requestId; }

    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public InventoryScope getScope() { return scope != null ? scope : InventoryScope.server(); }
    public void setScope(InventoryScope scope) { this.scope = scope != null ? scope : InventoryScope.server(); }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
}
