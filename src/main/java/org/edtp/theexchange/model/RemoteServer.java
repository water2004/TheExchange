package org.edtp.theexchange.model;

public class RemoteServer {
    private String name;
    private String address;
    private int port;
    private String passwordHash;
    private boolean enabled;

    public RemoteServer() {}

    public RemoteServer(String name, String address, int port, String passwordHash, boolean enabled) {
        this.name = name;
        this.address = address;
        this.port = port;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
