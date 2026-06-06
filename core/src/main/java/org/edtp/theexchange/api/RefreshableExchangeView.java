package org.edtp.theexchange.api;

import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;

public interface RefreshableExchangeView {
    boolean isViewingServer(String serverName);

    default boolean isViewingInventory(String serverName, InventoryAccess access) {
        return isViewingServer(serverName);
    }

    default boolean isViewingInventory(String serverName, InventoryScope scope) {
        return isViewingServer(serverName);
    }

    void refreshFromCache();

    void refreshFromMemory();
}
