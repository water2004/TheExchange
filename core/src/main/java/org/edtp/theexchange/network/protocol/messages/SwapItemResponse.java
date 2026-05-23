package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.NeutralItem;

public class SwapItemResponse implements CorrelatedMessage {
    private boolean success;
    private int slot;
    private NeutralItem currentItem;
    private NeutralItem takenItem;
    private int newVersion;
    private String failReason;
    private String requestId;

    public SwapItemResponse() {}

    public SwapItemResponse(boolean success, int slot, NeutralItem currentItem,
                            NeutralItem takenItem, int newVersion,
                            String failReason, String requestId) {
        this.success = success;
        this.slot = slot;
        this.currentItem = currentItem;
        this.takenItem = takenItem;
        this.newVersion = newVersion;
        this.failReason = failReason;
        this.requestId = requestId;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }

    public NeutralItem getCurrentItem() { return currentItem; }
    public void setCurrentItem(NeutralItem currentItem) { this.currentItem = currentItem; }

    public NeutralItem getTakenItem() { return takenItem; }
    public void setTakenItem(NeutralItem takenItem) { this.takenItem = takenItem; }

    public int getNewVersion() { return newVersion; }
    public void setNewVersion(int newVersion) { this.newVersion = newVersion; }

    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }

    @Override
    public String getRequestId() { return requestId; }

    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
