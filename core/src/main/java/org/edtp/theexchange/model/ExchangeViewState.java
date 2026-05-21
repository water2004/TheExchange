package org.edtp.theexchange.model;

import java.util.ArrayList;
import java.util.List;

public class ExchangeViewState {
    private String serverName;
    private boolean local;
    private boolean online;
    private long timestamp;
    private int rows = 6;
    private String titleTemplate = "{server_name} 的共享空间";
    private List<NeutralItem> items = new ArrayList<>();

    public static ExchangeViewState local(String serverName, List<NeutralItem> items, long timestamp) {
        ExchangeViewState state = new ExchangeViewState();
        state.serverName = serverName;
        state.local = true;
        state.online = true;
        state.timestamp = timestamp;
        state.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
        return state;
    }

    public static ExchangeViewState remote(String serverName, boolean online, List<NeutralItem> items, long timestamp) {
        ExchangeViewState state = new ExchangeViewState();
        state.serverName = serverName;
        state.local = false;
        state.online = online;
        state.timestamp = timestamp;
        state.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
        return state;
    }

    public String getServerName() { return serverName; }
    public boolean isLocal() { return local; }
    public boolean isOnline() { return online; }
    public long getTimestamp() { return timestamp; }
    public int getRows() { return rows; }
    public String getTitleTemplate() { return titleTemplate; }
    public List<NeutralItem> getItems() { return items; }

    public String getTitle(String localDisplayName) {
        String name = local ? localDisplayName : serverName;
        return (local ? "[本服] " : (online ? "" : "[离线] ")) + name + " 的共享空间";
    }
}
