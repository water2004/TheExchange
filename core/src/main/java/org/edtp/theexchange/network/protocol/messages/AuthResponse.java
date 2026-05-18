package org.edtp.theexchange.network.protocol.messages;

public class AuthResponse {
    private boolean success;
    private String message;
    private String serverName;
    private String mcVersion;
    private long lastModifiedTimestamp;

    public AuthResponse() {}

    public AuthResponse(boolean success, String message, String serverName, String mcVersion, long lastModifiedTimestamp) {
        this.success = success;
        this.message = message;
        this.serverName = serverName;
        this.mcVersion = mcVersion;
        this.lastModifiedTimestamp = lastModifiedTimestamp;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }

    public String getMcVersion() { return mcVersion; }
    public void setMcVersion(String mcVersion) { this.mcVersion = mcVersion; }

    public long getLastModifiedTimestamp() { return lastModifiedTimestamp; }
    public void setLastModifiedTimestamp(long lastModifiedTimestamp) { this.lastModifiedTimestamp = lastModifiedTimestamp; }
}
