package org.edtp.theexchange.network.protocol.messages;

public class AuthResponse {
    public static final String CURRENT_PROTOCOL_VERSION = "2";

    private boolean success;
    private String message;
    private String serverName;
    private String mcVersion;
    private long lastModifiedTimestamp;
    private String version = CURRENT_PROTOCOL_VERSION;

    public AuthResponse() {}

    public AuthResponse(boolean success, String message, String serverName, String mcVersion, long lastModifiedTimestamp) {
        this(success, message, serverName, mcVersion, lastModifiedTimestamp, CURRENT_PROTOCOL_VERSION);
    }

    public AuthResponse(boolean success, String message, String serverName, String mcVersion,
                        long lastModifiedTimestamp, String version) {
        this.success = success;
        this.message = message;
        this.serverName = serverName;
        this.mcVersion = mcVersion;
        this.lastModifiedTimestamp = lastModifiedTimestamp;
        this.version = version;
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

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
}
