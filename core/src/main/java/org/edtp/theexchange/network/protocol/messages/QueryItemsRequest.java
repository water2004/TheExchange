package org.edtp.theexchange.network.protocol.messages;

public class QueryItemsRequest {
    private int offset;
    private int limit;

    public QueryItemsRequest() {}

    public QueryItemsRequest(int offset, int limit) {
        this.offset = offset;
        this.limit = limit;
    }

    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = offset; }

    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
}
