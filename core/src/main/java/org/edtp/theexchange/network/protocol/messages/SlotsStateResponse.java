package org.edtp.theexchange.network.protocol.messages;

import java.util.ArrayList;
import java.util.List;

public class SlotsStateResponse {
    private List<SlotStateResponse> slots = new ArrayList<>();

    public SlotsStateResponse() {}

    public SlotsStateResponse(List<SlotStateResponse> slots) {
        this.slots = slots != null ? new ArrayList<>(slots) : new ArrayList<>();
    }

    public List<SlotStateResponse> getSlots() {
        return slots;
    }

    public void setSlots(List<SlotStateResponse> slots) {
        this.slots = slots != null ? new ArrayList<>(slots) : new ArrayList<>();
    }
}
