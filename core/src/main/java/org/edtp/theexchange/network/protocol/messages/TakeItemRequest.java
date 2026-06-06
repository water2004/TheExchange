package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.InventoryAccess;

public class TakeItemRequest implements CorrelatedMessage {
    private int slot;
    private String expectedItemId;
    private int expectedVersion;
    private int requestCount;
    private String requestId;
    private String playerUuid;
    private String playerName;
    private InventoryAccess access = InventoryAccess.server();

    public TakeItemRequest() {}

    public TakeItemRequest(int slot, String expectedItemId, int expectedVersion,
                           int requestCount, String requestId, String playerUuid, String playerName) {
        this(slot, expectedItemId, expectedVersion, requestCount, requestId,
                playerUuid, playerName, InventoryAccess.server());
    }

    public TakeItemRequest(int slot, String expectedItemId, int expectedVersion,
                           int requestCount, String requestId, String playerUuid, String playerName,
                           InventoryAccess access) {
        this.slot = slot;
        this.expectedItemId = expectedItemId;
        this.expectedVersion = expectedVersion;
        this.requestCount = requestCount;
        this.requestId = requestId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.access = access != null ? access : InventoryAccess.server();
    }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }

    public String getExpectedItemId() { return expectedItemId; }
    public void setExpectedItemId(String expectedItemId) { this.expectedItemId = expectedItemId; }

    public int getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(int expectedVersion) { this.expectedVersion = expectedVersion; }

    public int getRequestCount() { return requestCount; }
    public void setRequestCount(int requestCount) { this.requestCount = requestCount; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getPlayerUuid() { return playerUuid; }
    public void setPlayerUuid(String playerUuid) { this.playerUuid = playerUuid; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }

    public InventoryAccess getAccess() { return access != null ? access : InventoryAccess.server(); }
    public void setAccess(InventoryAccess access) { this.access = access != null ? access : InventoryAccess.server(); }
}
