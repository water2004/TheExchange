package org.edtp.theexchange.network.protocol.messages;

public class QuerySlotVersionsRequest implements CorrelatedMessage {
    private String requestId;

    public QuerySlotVersionsRequest() {}

    public QuerySlotVersionsRequest(String requestId) {
        this.requestId = requestId;
    }

    @Override
    public String getRequestId() { return requestId; }

    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
