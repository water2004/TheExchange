package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.NeutralItem;

public class PutItemRequest {
    private int slot;
    private NeutralItem item;
    private String requestId;
    private String playerUuid;
    private String playerName;

    public PutItemRequest() {}

    public PutItemRequest(int slot, NeutralItem item, String requestId, String playerUuid, String playerName) {
        this.slot = slot;
        this.item = item;
        this.requestId = requestId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
    }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }

    public NeutralItem getItem() { return item; }
    public void setItem(NeutralItem item) { this.item = item; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getPlayerUuid() { return playerUuid; }
    public void setPlayerUuid(String playerUuid) { this.playerUuid = playerUuid; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }
}
