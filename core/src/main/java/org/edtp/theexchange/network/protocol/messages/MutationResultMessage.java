package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;

public class MutationResultMessage {
    private String transactionId;
    private String intentHash;
    private String resultHash;
    private MutationKind kind;
    private boolean success;
    private int slot;
    private NeutralItem currentItem;
    private NeutralItem transferredItem;
    private String failReason;
    private long newTimestamp;
    private int newVersion;
    private InventoryScope scope = InventoryScope.server();

    public MutationResultMessage() {}

    public MutationResultMessage(String transactionId, String intentHash, String resultHash,
                                 MutationKind kind, boolean success, int slot,
                                 NeutralItem currentItem, NeutralItem transferredItem,
                                 String failReason, long newTimestamp, int newVersion,
                                 InventoryScope scope) {
        this.transactionId = transactionId;
        this.intentHash = intentHash;
        this.resultHash = resultHash;
        this.kind = kind;
        this.success = success;
        this.slot = slot;
        this.currentItem = currentItem;
        this.transferredItem = transferredItem;
        this.failReason = failReason;
        this.newTimestamp = newTimestamp;
        this.newVersion = newVersion;
        this.scope = scope != null ? scope : InventoryScope.server();
    }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public String getIntentHash() { return intentHash; }
    public void setIntentHash(String intentHash) { this.intentHash = intentHash; }
    public String getResultHash() { return resultHash; }
    public void setResultHash(String resultHash) { this.resultHash = resultHash; }
    public MutationKind getKind() { return kind; }
    public void setKind(MutationKind kind) { this.kind = kind; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }
    public NeutralItem getCurrentItem() { return currentItem; }
    public void setCurrentItem(NeutralItem currentItem) { this.currentItem = currentItem; }
    public NeutralItem getTransferredItem() { return transferredItem; }
    public void setTransferredItem(NeutralItem transferredItem) { this.transferredItem = transferredItem; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public long getNewTimestamp() { return newTimestamp; }
    public void setNewTimestamp(long newTimestamp) { this.newTimestamp = newTimestamp; }
    public int getNewVersion() { return newVersion; }
    public void setNewVersion(int newVersion) { this.newVersion = newVersion; }
    public InventoryScope getScope() { return scope != null ? scope : InventoryScope.server(); }
    public void setScope(InventoryScope scope) { this.scope = scope != null ? scope : InventoryScope.server(); }
}
