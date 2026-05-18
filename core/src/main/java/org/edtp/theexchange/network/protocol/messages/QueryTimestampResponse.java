package org.edtp.theexchange.network.protocol.messages;

public class QueryTimestampResponse {
    private long currentTimestamp;
    private boolean changed;

    public QueryTimestampResponse() {}

    public QueryTimestampResponse(long currentTimestamp, boolean changed) {
        this.currentTimestamp = currentTimestamp;
        this.changed = changed;
    }

    public long getCurrentTimestamp() { return currentTimestamp; }
    public void setCurrentTimestamp(long currentTimestamp) { this.currentTimestamp = currentTimestamp; }

    public boolean isChanged() { return changed; }
    public void setChanged(boolean changed) { this.changed = changed; }
}
