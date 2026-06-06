package org.edtp.theexchange.network.protocol.messages;

public class AuthRequest {
    public static final String CURRENT_PROTOCOL_VERSION = "2";

    private String serverName;
    private String password;      // plaintext over TLS, compared directly on receiver side
    private String version;       // protocol version
    private String mcVersion;     // MC version string

    public AuthRequest() {}

    public AuthRequest(String serverName, String password, String version, String mcVersion) {
        this.serverName = serverName;
        this.password = password;
        this.version = version;
        this.mcVersion = mcVersion;
    }

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getMcVersion() { return mcVersion; }
    public void setMcVersion(String mcVersion) { this.mcVersion = mcVersion; }
}
