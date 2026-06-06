package org.edtp.theexchange.network.protocol.messages;

import org.edtp.theexchange.model.InventoryScope;

import java.util.ArrayList;
import java.util.List;

public class SlotVersionsResponse implements CorrelatedMessage {
    private String requestId;
    private List<Integer> versions = new ArrayList<>();
    private InventoryScope scope = InventoryScope.server();
    private boolean success = true;
    private String failReason;

    public SlotVersionsResponse() {}

    public SlotVersionsResponse(List<Integer> versions) {
        this(null, versions);
    }

    public SlotVersionsResponse(String requestId, List<Integer> versions) {
        this(requestId, versions, InventoryScope.server());
    }

    public SlotVersionsResponse(String requestId, List<Integer> versions, InventoryScope scope) {
        this.requestId = requestId;
        this.versions = versions != null ? new ArrayList<>(versions) : new ArrayList<>();
        this.scope = scope != null ? scope : InventoryScope.server();
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

    public InventoryScope getScope() { return scope != null ? scope : InventoryScope.server(); }
    public void setScope(InventoryScope scope) { this.scope = scope != null ? scope : InventoryScope.server(); }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
}
