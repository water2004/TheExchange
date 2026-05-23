package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.NeutralItem;

public class SwapItemRequest implements CorrelatedMessage {
    private int slot;
    private NeutralItem newItem;
    private int expectedVersion;
    private String expectedItemId;
    private int takeCount;
    private String requestId;
    private String playerUuid;
    private String playerName;

    public SwapItemRequest() {}

    public SwapItemRequest(int slot, NeutralItem newItem, int expectedVersion,
                           String expectedItemId, int takeCount, String requestId,
                           String playerUuid, String playerName) {
        this.slot = slot;
        this.newItem = newItem;
        this.expectedVersion = expectedVersion;
        this.expectedItemId = expectedItemId;
        this.takeCount = takeCount;
        this.requestId = requestId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
    }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }

    public NeutralItem getNewItem() { return newItem; }
    public void setNewItem(NeutralItem newItem) { this.newItem = newItem; }

    public int getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(int expectedVersion) { this.expectedVersion = expectedVersion; }

    public String getExpectedItemId() { return expectedItemId; }
    public void setExpectedItemId(String expectedItemId) { this.expectedItemId = expectedItemId; }

    public int getTakeCount() { return takeCount; }
    public void setTakeCount(int takeCount) { this.takeCount = takeCount; }

    @Override
    public String getRequestId() { return requestId; }

    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getPlayerUuid() { return playerUuid; }
    public void setPlayerUuid(String playerUuid) { this.playerUuid = playerUuid; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }
}
