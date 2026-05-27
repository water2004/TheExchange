package org.edtp.theexchange.network.protocol.messages;

import java.util.List;

public class PushUpdate {
    private List<Integer> changedSlots;
    private long timestamp;

    public PushUpdate() {}

    public PushUpdate(List<Integer> changedSlots, long timestamp) {
        this.changedSlots = changedSlots;
        this.timestamp = timestamp;
    }

    public List<Integer> getChangedSlots() { return changedSlots; }
    public void setChangedSlots(List<Integer> changedSlots) { this.changedSlots = changedSlots; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
