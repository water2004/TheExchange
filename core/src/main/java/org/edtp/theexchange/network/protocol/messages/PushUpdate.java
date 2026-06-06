package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.InventoryScope;

import java.util.List;

public class PushUpdate {
    private List<Integer> changedSlots;
    private long timestamp;
    private InventoryScope scope = InventoryScope.server();

    public PushUpdate() {}

    public PushUpdate(List<Integer> changedSlots, long timestamp) {
        this(changedSlots, timestamp, InventoryScope.server());
    }

    public PushUpdate(List<Integer> changedSlots, long timestamp, InventoryScope scope) {
        this.changedSlots = changedSlots;
        this.timestamp = timestamp;
        this.scope = scope != null ? scope : InventoryScope.server();
    }

    public List<Integer> getChangedSlots() { return changedSlots; }
    public void setChangedSlots(List<Integer> changedSlots) { this.changedSlots = changedSlots; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public InventoryScope getScope() { return scope != null ? scope : InventoryScope.server(); }
    public void setScope(InventoryScope scope) { this.scope = scope != null ? scope : InventoryScope.server(); }
}
