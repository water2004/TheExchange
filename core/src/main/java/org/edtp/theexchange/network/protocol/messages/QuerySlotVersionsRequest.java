package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.InventoryAccess;

public class QuerySlotVersionsRequest implements CorrelatedMessage {
    private String requestId;
    private InventoryAccess access = InventoryAccess.server();

    public QuerySlotVersionsRequest() {}

    public QuerySlotVersionsRequest(String requestId) {
        this(requestId, InventoryAccess.server());
    }

    public QuerySlotVersionsRequest(String requestId, InventoryAccess access) {
        this.requestId = requestId;
        this.access = access != null ? access : InventoryAccess.server();
    }

    @Override
    public String getRequestId() { return requestId; }

    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public InventoryAccess getAccess() { return access != null ? access : InventoryAccess.server(); }
    public void setAccess(InventoryAccess access) { this.access = access != null ? access : InventoryAccess.server(); }
}
