package org.edtp.theexchange.network.protocol.messages;

public interface CorrelatedMessage {
    String getRequestId();
    void setRequestId(String requestId);
}
