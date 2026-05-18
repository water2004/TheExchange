package org.edtp.theexchange.network.protocol.messages;

public class QueryTimestampRequest {
    private long cachedTimestamp;

    public QueryTimestampRequest() {}

    public QueryTimestampRequest(long cachedTimestamp) {
        this.cachedTimestamp = cachedTimestamp;
    }

    public long getCachedTimestamp() { return cachedTimestamp; }
    public void setCachedTimestamp(long cachedTimestamp) { this.cachedTimestamp = cachedTimestamp; }
}
