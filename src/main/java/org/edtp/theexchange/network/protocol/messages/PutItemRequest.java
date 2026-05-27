package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.NeutralItem;

public class PutItemRequest implements CorrelatedMessage {
    private int slot;
    private NeutralItem item;
    private int expectedVersion;
    private String requestId;
    private String playerUuid;
    private String playerName;

    public PutItemRequest() {}

    public PutItemRequest(int slot, NeutralItem item, String requestId, String playerUuid, String playerName) {
        this(slot, item, 0, requestId, playerUuid, playerName);
    }

    public PutItemRequest(int slot, NeutralItem item, int expectedVersion,
                          String requestId, String playerUuid, String playerName) {
        this.slot = slot;
        this.item = item;
        this.expectedVersion = expectedVersion;
        this.requestId = requestId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
    }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }

    public NeutralItem getItem() { return item; }
    public void setItem(NeutralItem item) { this.item = item; }

    public int getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(int expectedVersion) { this.expectedVersion = expectedVersion; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getPlayerUuid() { return playerUuid; }
    public void setPlayerUuid(String playerUuid) { this.playerUuid = playerUuid; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }
}
