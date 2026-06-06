package org.edtp.theexchange.model;

import java.util.Objects;

public final class InventoryAccess {
    private final InventoryScope.ScopeType type;
    private final String ownerName;
    private final String password;
    private final InventoryScope resolvedScope;

    private InventoryAccess(InventoryScope.ScopeType type, String ownerName,
                            String password, InventoryScope resolvedScope) {
        this.type = type != null ? type : InventoryScope.ScopeType.SERVER;
        this.ownerName = ownerName != null ? ownerName.trim() : "";
        this.password = password != null ? password : "";
        this.resolvedScope = resolvedScope;
    }

    public static InventoryAccess server() {
        return new InventoryAccess(InventoryScope.ScopeType.SERVER, "", "", InventoryScope.server());
    }

    public static InventoryAccess player(String ownerName, String password) {
        return new InventoryAccess(InventoryScope.ScopeType.PLAYER, ownerName, password, null);
    }

    public InventoryAccess withResolvedScope(InventoryScope scope) {
        return new InventoryAccess(type, ownerName, password, scope);
    }

    public InventoryScope.ScopeType type() {
        return type;
    }

    public String ownerName() {
        return ownerName;
    }

    public String password() {
        return password;
    }

    public InventoryScope resolvedScope() {
        return resolvedScope;
    }

    public InventoryScope effectiveScope() {
        if (resolvedScope != null) {
            return resolvedScope;
        }
        return type == InventoryScope.ScopeType.SERVER ? InventoryScope.server() : null;
    }

    public boolean isServer() {
        return type == InventoryScope.ScopeType.SERVER;
    }

    public boolean isPlayer() {
        return type == InventoryScope.ScopeType.PLAYER;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InventoryAccess that)) return false;
        return type == that.type
                && Objects.equals(ownerName, that.ownerName)
                && Objects.equals(password, that.password)
                && Objects.equals(resolvedScope, that.resolvedScope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, ownerName, password, resolvedScope);
    }
}
