package org.edtp.theexchange.fabric.automation;

import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.PlayerInventoryConnectionSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerWarehouseAutomationSessionsTest {
    @AfterEach
    void tearDown() throws Exception {
        sessions().clear();
        PlayerWarehouseHopperBridge.reset();
    }

    @Test
    void hopperResetClearsDelegatedPlayerSessions() throws Exception {
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        InventoryAccess access = InventoryAccess.playerSession(
                "Steve", "token", "requester", "Viewer", scope, Long.MAX_VALUE, 60_000L);
        PlayerWarehouseAutomationSessions.remember(
                "minecraft:overworld:1", PlayerInventoryConnectionSpec.parse("Steve@remote"), access);
        assertEquals(1, sessions().size());

        PlayerWarehouseHopperBridge.reset();

        assertEquals(0, sessions().size(),
                "reload/shutdown reset must release delegated endpoint sessions immediately");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> sessions() throws Exception {
        Field field = PlayerWarehouseAutomationSessions.class.getDeclaredField("SESSIONS");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(null);
    }
}
