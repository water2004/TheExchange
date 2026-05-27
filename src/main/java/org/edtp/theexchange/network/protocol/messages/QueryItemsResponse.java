package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.NeutralItem;
import java.util.List;

public class QueryItemsResponse {
    private List<NeutralItem> items;
    private int totalSlots;
    private long timestamp;
    private String serverVersion;

    public QueryItemsResponse() {}

    public QueryItemsResponse(List<NeutralItem> items, int totalSlots, long timestamp, String serverVersion) {
        this.items = items;
        this.totalSlots = totalSlots;
        this.timestamp = timestamp;
        this.serverVersion = serverVersion;
    }

    public List<NeutralItem> getItems() { return items; }
    public void setItems(List<NeutralItem> items) { this.items = items; }

    public int getTotalSlots() { return totalSlots; }
    public void setTotalSlots(int totalSlots) { this.totalSlots = totalSlots; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getServerVersion() { return serverVersion; }
    public void setServerVersion(String serverVersion) { this.serverVersion = serverVersion; }
}
