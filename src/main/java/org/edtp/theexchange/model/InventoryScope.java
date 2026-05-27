package org.edtp.theexchange.model;

import java.util.Objects;

public class InventoryScope {
    public enum ScopeType {
        SERVER,
        PLAYER
    }

    private final ScopeType type;
    private final String scopeId;

    public InventoryScope(ScopeType type, String scopeId) {
        this.type = type != null ? type : ScopeType.SERVER;
        this.scopeId = scopeId != null ? scopeId : "";
    }

    public static InventoryScope server() {
        return new InventoryScope(ScopeType.SERVER, "");
    }

    public static InventoryScope player(String playerUuid) {
        return new InventoryScope(ScopeType.PLAYER, playerUuid);
    }

    public ScopeType getType() {
        return type;
    }

    public String getScopeId() {
        return scopeId;
    }

    public String typeName() {
        return type.name();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InventoryScope that)) return false;
        return type == that.type && Objects.equals(scopeId, that.scopeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, scopeId);
    }
}
