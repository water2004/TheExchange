package org.edtp.theexchange.network.protocol.messages;

import java.util.LinkedHashMap;
import java.util.Map;

public class SlotVersionsResponse {
    private Map<Integer, Integer> versions = new LinkedHashMap<>();

    public SlotVersionsResponse() {}

    public SlotVersionsResponse(Map<Integer, Integer> versions) {
        this.versions = versions != null ? new LinkedHashMap<>(versions) : new LinkedHashMap<>();
    }

    public Map<Integer, Integer> getVersions() {
        return versions;
    }

    public void setVersions(Map<Integer, Integer> versions) {
        this.versions = versions != null ? new LinkedHashMap<>(versions) : new LinkedHashMap<>();
    }
}
