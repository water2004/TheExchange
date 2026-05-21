package org.edtp.theexchange.network.protocol.messages;

import java.util.ArrayList;
import java.util.List;

public class QuerySlotsRequest {
    private List<Integer> slots = new ArrayList<>();

    public QuerySlotsRequest() {}

    public QuerySlotsRequest(List<Integer> slots) {
        this.slots = slots != null ? new ArrayList<>(slots) : new ArrayList<>();
    }

    public List<Integer> getSlots() {
        return slots;
    }

    public void setSlots(List<Integer> slots) {
        this.slots = slots != null ? new ArrayList<>(slots) : new ArrayList<>();
    }
}
