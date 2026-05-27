package org.edtp.theexchange.api;

public interface RefreshableExchangeView {
    boolean isViewingServer(String serverName);

    void refreshFromCache();

    void refreshFromMemory();
}
