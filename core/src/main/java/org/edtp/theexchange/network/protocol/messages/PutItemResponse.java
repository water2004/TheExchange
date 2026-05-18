package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.NeutralItem;

public class PutItemResponse {
    private boolean success;
    private int slot;
    private NeutralItem currentItem;
    private String failReason;
    private long newTimestamp;
    private int newVersion;

    public PutItemResponse() {}

    public PutItemResponse(boolean success, int slot, NeutralItem currentItem,
                           String failReason, long newTimestamp, int newVersion) {
        this.success = success;
        this.slot = slot;
        this.currentItem = currentItem;
        this.failReason = failReason;
        this.newTimestamp = newTimestamp;
        this.newVersion = newVersion;
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
}
