package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.NeutralItem;

public class MutationExecute {
    private String transactionId;
    private String intentHash;
    private MutationKind kind;
    private int slot;
    private NeutralItem offeredItem;
    private String expectedItemId;
    private int expectedVersion;
    private int count;
    private boolean boundedMerge;
    private String playerUuid;
    private String playerName;
    private InventoryAccess access = InventoryAccess.server();

    public MutationExecute() {}

    public MutationExecute(String transactionId, String intentHash, MutationKind kind,
                           int slot, NeutralItem offeredItem, String expectedItemId,
                           int expectedVersion, int count, boolean boundedMerge,
                           String playerUuid, String playerName, InventoryAccess access) {
        this.transactionId = transactionId;
        this.intentHash = intentHash;
        this.kind = kind;
        this.slot = slot;
        this.offeredItem = offeredItem;
        this.expectedItemId = expectedItemId;
        this.expectedVersion = expectedVersion;
        this.count = count;
        this.boundedMerge = boundedMerge;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.access = access != null ? access : InventoryAccess.server();
    }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public String getIntentHash() { return intentHash; }
    public void setIntentHash(String intentHash) { this.intentHash = intentHash; }
    public MutationKind getKind() { return kind; }
    public void setKind(MutationKind kind) { this.kind = kind; }
    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }
    public NeutralItem getOfferedItem() { return offeredItem; }
    public void setOfferedItem(NeutralItem offeredItem) { this.offeredItem = offeredItem; }
    public String getExpectedItemId() { return expectedItemId; }
    public void setExpectedItemId(String expectedItemId) { this.expectedItemId = expectedItemId; }
    public int getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(int expectedVersion) { this.expectedVersion = expectedVersion; }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
    public boolean isBoundedMerge() { return boundedMerge; }
    public void setBoundedMerge(boolean boundedMerge) { this.boundedMerge = boundedMerge; }
    public String getPlayerUuid() { return playerUuid; }
    public void setPlayerUuid(String playerUuid) { this.playerUuid = playerUuid; }
    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }
    public InventoryAccess getAccess() { return access != null ? access : InventoryAccess.server(); }
    public void setAccess(InventoryAccess access) { this.access = access != null ? access : InventoryAccess.server(); }
}
