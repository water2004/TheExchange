package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.InventoryAccess;

import java.util.ArrayList;
import java.util.List;

public class QuerySlotsRequest implements CorrelatedMessage {
    private String requestId;
    private List<Integer> slots = new ArrayList<>();
    private InventoryAccess access = InventoryAccess.server();

    public QuerySlotsRequest() {}

    public QuerySlotsRequest(List<Integer> slots) {
        this(null, slots);
    }

    public QuerySlotsRequest(String requestId, List<Integer> slots) {
        this(requestId, slots, InventoryAccess.server());
    }

    public QuerySlotsRequest(String requestId, List<Integer> slots, InventoryAccess access) {
        this.requestId = requestId;
        this.slots = slots != null ? new ArrayList<>(slots) : new ArrayList<>();
        this.access = access != null ? access : InventoryAccess.server();
    }

    @Override
    public String getRequestId() { return requestId; }

    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public List<Integer> getSlots() {
        return slots;
    }

    public void setSlots(List<Integer> slots) {
        this.slots = slots != null ? new ArrayList<>(slots) : new ArrayList<>();
    }

    public InventoryAccess getAccess() { return access != null ? access : InventoryAccess.server(); }
    public void setAccess(InventoryAccess access) { this.access = access != null ? access : InventoryAccess.server(); }
}
