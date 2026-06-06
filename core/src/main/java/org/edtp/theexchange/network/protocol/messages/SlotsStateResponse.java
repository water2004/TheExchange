package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.InventoryScope;

import java.util.ArrayList;
import java.util.List;

public class SlotsStateResponse implements CorrelatedMessage {
    private String requestId;
    private List<SlotStateResponse> slots = new ArrayList<>();
    private InventoryScope scope = InventoryScope.server();
    private boolean success = true;
    private String failReason;

    public SlotsStateResponse() {}

    public SlotsStateResponse(List<SlotStateResponse> slots) {
        this(null, slots);
    }

    public SlotsStateResponse(String requestId, List<SlotStateResponse> slots) {
        this(requestId, slots, InventoryScope.server());
    }

    public SlotsStateResponse(String requestId, List<SlotStateResponse> slots, InventoryScope scope) {
        this.requestId = requestId;
        this.slots = slots != null ? new ArrayList<>(slots) : new ArrayList<>();
        this.scope = scope != null ? scope : InventoryScope.server();
    }

    @Override
    public String getRequestId() { return requestId; }

    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public List<SlotStateResponse> getSlots() {
        return slots;
    }

    public void setSlots(List<SlotStateResponse> slots) {
        this.slots = slots != null ? new ArrayList<>(slots) : new ArrayList<>();
    }

    public InventoryScope getScope() { return scope != null ? scope : InventoryScope.server(); }
    public void setScope(InventoryScope scope) { this.scope = scope != null ? scope : InventoryScope.server(); }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
}
