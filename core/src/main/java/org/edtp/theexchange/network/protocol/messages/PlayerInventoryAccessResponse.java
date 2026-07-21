package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.InventoryScope;

public final class PlayerInventoryAccessResponse implements CorrelatedMessage {
    private String requestId;
    private boolean success;
    private String failReason;
    private String ownerName;
    private String token;
    private InventoryScope scope;
    private long expiresAt;
    private long sessionTtlMillis;
    private long lockedUntil;

    public PlayerInventoryAccessResponse() {
    }

    public static PlayerInventoryAccessResponse success(String requestId, String ownerName,
                                                        String token, InventoryScope scope,
                                                        long expiresAt, long sessionTtlMillis) {
        PlayerInventoryAccessResponse response = new PlayerInventoryAccessResponse();
        response.requestId = requestId;
        response.success = true;
        response.ownerName = ownerName;
        response.token = token;
        response.scope = scope;
        response.expiresAt = expiresAt;
        response.sessionTtlMillis = sessionTtlMillis;
        return response;
    }

    public static PlayerInventoryAccessResponse fail(String requestId, String failReason,
                                                     long lockedUntil) {
        PlayerInventoryAccessResponse response = new PlayerInventoryAccessResponse();
        response.requestId = requestId;
        response.failReason = failReason;
        response.lockedUntil = lockedUntil;
        return response;
    }

    @Override
    public String getRequestId() { return requestId; }
    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public InventoryScope getScope() { return scope; }
    public void setScope(InventoryScope scope) { this.scope = scope; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
    public long getSessionTtlMillis() { return sessionTtlMillis; }
    public void setSessionTtlMillis(long sessionTtlMillis) { this.sessionTtlMillis = sessionTtlMillis; }
    public long getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(long lockedUntil) { this.lockedUntil = lockedUntil; }
}
