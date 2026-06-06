package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.InventoryScope;

public class PutItemResponse implements CorrelatedMessage {
    private boolean success;
    private int slot;
    private NeutralItem currentItem;
    private String failReason;
    private long newTimestamp;
    private int newVersion;
    private String requestId;
    private InventoryScope scope = InventoryScope.server();

    public PutItemResponse() {}

    public PutItemResponse(boolean success, int slot, NeutralItem currentItem,
                           String failReason, long newTimestamp, int newVersion) {
        this(success, slot, currentItem, failReason, newTimestamp, newVersion, null);
    }

    public PutItemResponse(boolean success, int slot, NeutralItem currentItem,
                           String failReason, long newTimestamp, int newVersion, String requestId) {
        this(success, slot, currentItem, failReason, newTimestamp, newVersion, requestId, InventoryScope.server());
    }

    public PutItemResponse(boolean success, int slot, NeutralItem currentItem,
                           String failReason, long newTimestamp, int newVersion,
                           String requestId, InventoryScope scope) {
        this.success = success;
        this.slot = slot;
        this.currentItem = currentItem;
        this.failReason = failReason;
        this.newTimestamp = newTimestamp;
        this.newVersion = newVersion;
        this.requestId = requestId;
        this.scope = scope != null ? scope : InventoryScope.server();
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }

    public NeutralItem getCurrentItem() { return currentItem; }
    public void setCurrentItem(NeutralItem currentItem) { this.currentItem = currentItem; }

    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }

    public long getNewTimestamp() { return newTimestamp; }
    public void setNewTimestamp(long newTimestamp) { this.newTimestamp = newTimestamp; }

    public int getNewVersion() { return newVersion; }
    public void setNewVersion(int newVersion) { this.newVersion = newVersion; }

    @Override
    public String getRequestId() { return requestId; }

    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public InventoryScope getScope() { return scope != null ? scope : InventoryScope.server(); }
    public void setScope(InventoryScope scope) { this.scope = scope != null ? scope : InventoryScope.server(); }
}
