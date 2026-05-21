package org.edtp.theexchange.network.protocol.messages;

import java.util.ArrayList;
import java.util.List;

public class QuerySlotsRequest implements CorrelatedMessage {
    private String requestId;
    private List<Integer> slots = new ArrayList<>();

    public QuerySlotsRequest() {}

    public QuerySlotsRequest(List<Integer> slots) {
        this(null, slots);
    }

    public QuerySlotsRequest(String requestId, List<Integer> slots) {
        this.requestId = requestId;
        this.slots = slots != null ? new ArrayList<>(slots) : new ArrayList<>();
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
}
