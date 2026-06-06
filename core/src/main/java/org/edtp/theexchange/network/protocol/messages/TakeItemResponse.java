package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.model.InventoryScope;

public class TakeItemResponse implements CorrelatedMessage {
    private boolean success;
    private int slot;
    private NeutralItem currentItem;
    private String failReason;
    private long newTimestamp;
    private int newVersion;
    private NeutralItem itemsToGive;
    private String requestId;
    private InventoryScope scope = InventoryScope.server();

    public TakeItemResponse() {}

    public TakeItemResponse(boolean success, int slot, NeutralItem currentItem,
                            String failReason, long newTimestamp, int newVersion,
                            NeutralItem itemsToGive) {
        this(success, slot, currentItem, failReason, newTimestamp, newVersion, itemsToGive, null);
    }

    public TakeItemResponse(boolean success, int slot, NeutralItem currentItem,
                            String failReason, long newTimestamp, int newVersion,
                            NeutralItem itemsToGive, String requestId) {
        this(success, slot, currentItem, failReason, newTimestamp, newVersion,
                itemsToGive, requestId, InventoryScope.server());
    }

    public TakeItemResponse(boolean success, int slot, NeutralItem currentItem,
                            String failReason, long newTimestamp, int newVersion,
                            NeutralItem itemsToGive, String requestId, InventoryScope scope) {
        this.success = success;
        this.slot = slot;
        this.currentItem = currentItem;
        this.failReason = failReason;
        this.newTimestamp = newTimestamp;
        this.newVersion = newVersion;
        this.itemsToGive = itemsToGive;
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

    public NeutralItem getItemsToGive() { return itemsToGive; }
    public void setItemsToGive(NeutralItem itemsToGive) { this.itemsToGive = itemsToGive; }

    @Override
    public String getRequestId() { return requestId; }

    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public InventoryScope getScope() { return scope != null ? scope : InventoryScope.server(); }
    public void setScope(InventoryScope scope) { this.scope = scope != null ? scope : InventoryScope.server(); }
}
