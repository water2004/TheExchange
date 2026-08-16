package org.edtp.theexchange.fabric.automation;

import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.PlayerExchangeContext;
import org.edtp.theexchange.model.PlayerInventoryConnectionSpec;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit, process-local delegation from a player opening one signed chest to that chest's automation. */
public final class PlayerWarehouseAutomationSessions {
    private static final ConcurrentHashMap<String, DelegatedSession> SESSIONS = new ConcurrentHashMap<>();

    private PlayerWarehouseAutomationSessions() {
    }

    public static void remember(String endpointId, PlayerInventoryConnectionSpec connection,
                                InventoryAccess access) {
        if (endpointId == null || endpointId.isBlank() || access == null || !access.isPlayer()) return;
        SESSIONS.put(endpointId, new DelegatedSession(normalize(connection.serverName()),
                normalize(connection.playerName()), access));
    }

    public static Optional<InventoryAccess> find(TheExchangeCore core, String endpointId,
                                                  PlayerInventoryConnectionSpec connection) {
        DelegatedSession delegated = SESSIONS.get(endpointId);
        if (delegated == null || !delegated.serverName().equals(normalize(connection.serverName()))
                || !delegated.ownerName().equals(normalize(connection.playerName()))) {
            return Optional.empty();
        }
        InventoryAccess descriptor = delegated.access();
        Optional<InventoryAccess> current = core.findPlayerInventorySession(
                connection.serverName(), connection.playerName(),
                new PlayerExchangeContext(descriptor.requesterUuid(), descriptor.requesterName()));
        if (current.isEmpty()) SESSIONS.remove(endpointId, delegated);
        return current;
    }

    public static void clear() {
        SESSIONS.clear();
    }

    private static String normalize(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private record DelegatedSession(String serverName, String ownerName, InventoryAccess access) {
    }
}
