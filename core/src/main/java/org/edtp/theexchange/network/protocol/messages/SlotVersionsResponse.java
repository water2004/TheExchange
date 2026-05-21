package org.edtp.theexchange.network.protocol.messages;

import java.util.ArrayList;
import java.util.List;

public class SlotVersionsResponse {
    private List<Integer> versions = new ArrayList<>();

    public SlotVersionsResponse() {}

    public SlotVersionsResponse(List<Integer> versions) {
        this.versions = versions != null ? new ArrayList<>(versions) : new ArrayList<>();
    }

    public List<Integer> getVersions() {
        return versions;
    }

    public void setVersions(List<Integer> versions) {
        this.versions = versions != null ? new ArrayList<>(versions) : new ArrayList<>();
    }
}
