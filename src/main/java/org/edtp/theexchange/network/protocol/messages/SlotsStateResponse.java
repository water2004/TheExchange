package org.edtp.theexchange.network.protocol.messages;

import java.util.ArrayList;
import java.util.List;

public class SlotsStateResponse implements CorrelatedMessage {
    private String requestId;
    private List<SlotStateResponse> slots = new ArrayList<>();

    public SlotsStateResponse() {}

    public SlotsStateResponse(List<SlotStateResponse> slots) {
        this(null, slots);
    }

    public SlotsStateResponse(String requestId, List<SlotStateResponse> slots) {
        this.requestId = requestId;
        this.slots = slots != null ? new ArrayList<>(slots) : new ArrayList<>();
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
}
