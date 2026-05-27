package org.edtp.theexchange.network.protocol.messages;

public class Heartbeat {
    private boolean isReply;
    private long timestamp;

    public Heartbeat() {}

    public Heartbeat(boolean isReply, long timestamp) {
        this.isReply = isReply;
        this.timestamp = timestamp;
    }

    public boolean isReply() { return isReply; }
    public void setReply(boolean reply) { isReply = reply; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
