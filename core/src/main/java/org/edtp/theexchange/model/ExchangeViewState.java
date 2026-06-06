package org.edtp.theexchange.model;

import java.util.ArrayList;
import java.util.List;

public class ExchangeViewState {
    private String serverName;
    private boolean local;
    private boolean online;
    private long timestamp;
    private int rows = 6;
    private InventoryAccess access = InventoryAccess.server();
    private InventoryScope scope = InventoryScope.server();
    private List<NeutralItem> items = new ArrayList<>();

    public static ExchangeViewState local(String serverName, List<NeutralItem> items, long timestamp) {
        return local(serverName, items, timestamp, InventoryScope.server(), InventoryAccess.server());
    }

    public static ExchangeViewState local(String serverName, List<NeutralItem> items, long timestamp,
                                          InventoryScope scope, InventoryAccess access) {
        ExchangeViewState state = new ExchangeViewState();
        state.serverName = serverName;
        state.local = true;
        state.online = true;
        state.timestamp = timestamp;
        state.scope = scope != null ? scope : InventoryScope.server();
        state.access = access != null ? access.withResolvedScope(state.scope) : InventoryAccess.server();
        state.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
        return state;
    }

    public static ExchangeViewState remote(String serverName, boolean online, List<NeutralItem> items, long timestamp) {
        return remote(serverName, online, items, timestamp, InventoryScope.server(), InventoryAccess.server());
    }

    public static ExchangeViewState remote(String serverName, boolean online, List<NeutralItem> items,
                                           long timestamp, InventoryScope scope, InventoryAccess access) {
        ExchangeViewState state = new ExchangeViewState();
        state.serverName = serverName;
        state.local = false;
        state.online = online;
        state.timestamp = timestamp;
        state.scope = scope != null ? scope : InventoryScope.server();
        state.access = access != null ? access.withResolvedScope(state.scope) : InventoryAccess.server();
        state.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
        return state;
    }

    public String getServerName() { return serverName; }
    public boolean isLocal() { return local; }
    public boolean isOnline() { return online; }
    public long getTimestamp() { return timestamp; }
    public int getRows() { return rows; }
    public InventoryAccess getAccess() { return access != null ? access : InventoryAccess.server(); }
    public InventoryScope getScope() { return scope != null ? scope : InventoryScope.server(); }
    public List<NeutralItem> getItems() { return items; }

    public String getTitle(String localDisplayName) {
        String name = local ? localDisplayName : serverName;
        String kind = getScope().isPlayer() ? "玩家仓库" : "共享空间";
        String owner = getScope().isPlayer() && getAccess().ownerName() != null && !getAccess().ownerName().isBlank()
                ? " (" + getAccess().ownerName() + ")"
                : "";
        return (local ? "[本服] " : (online ? "" : "[离线] ")) + name + owner + " 的" + kind;
    }
}
