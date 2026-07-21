package org.edtp.theexchange.network.protocol.messages;

public final class PlayerInventoryAccessRequest implements CorrelatedMessage {
    private String requestId;
    private String ownerName;
    private String password;
    private String requesterUuid;
    private String requesterName;

    public PlayerInventoryAccessRequest() {
    }

    public PlayerInventoryAccessRequest(String requestId, String ownerName, String password,
                                        String requesterUuid, String requesterName) {
        this.requestId = requestId;
        this.ownerName = ownerName;
        this.password = password;
        this.requesterUuid = requesterUuid;
        this.requesterName = requesterName;
    }

    @Override
    public String getRequestId() { return requestId; }
    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getRequesterUuid() { return requesterUuid; }
    public void setRequesterUuid(String requesterUuid) { this.requesterUuid = requesterUuid; }
    public String getRequesterName() { return requesterName; }
    public void setRequesterName(String requesterName) { this.requesterName = requesterName; }
}
