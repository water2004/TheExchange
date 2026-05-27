package org.edtp.theexchange.network.protocol.messages;

import java.util.ArrayList;
import java.util.List;

public class SlotVersionsResponse implements CorrelatedMessage {
    private String requestId;
    private List<Integer> versions = new ArrayList<>();

    public SlotVersionsResponse() {}

    public SlotVersionsResponse(List<Integer> versions) {
        this(null, versions);
    }

    public SlotVersionsResponse(String requestId, List<Integer> versions) {
        this.requestId = requestId;
        this.versions = versions != null ? new ArrayList<>(versions) : new ArrayList<>();
    }

    @Override
    public String getRequestId() { return requestId; }

    @Override
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public List<Integer> getVersions() {
        return versions;
    }

    public void setVersions(List<Integer> versions) {
        this.versions = versions != null ? new ArrayList<>(versions) : new ArrayList<>();
    }
}
