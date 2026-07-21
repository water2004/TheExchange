package org.edtp.theexchange.fabric.player;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.fabric.container.ExchangeMenu;
import org.edtp.theexchange.fabric.automation.PlayerWarehouseAutomationSessions;
import org.edtp.theexchange.model.ExchangeViewState;
import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.PlayerExchangeContext;
import org.edtp.theexchange.model.PlayerInventoryConnectionSpec;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates command and attached-ender-chest player authentication without retaining passwords. */
public final class PlayerWarehouseAccessCoordinator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long PENDING_PASSWORD_TTL_MS = 120_000L;
    private static final ConcurrentHashMap<String, PendingConnection> PENDING = new ConcurrentHashMap<>();

    private PlayerWarehouseAccessCoordinator() {
    }

    public static void requestOpen(ServerPlayer player, PlayerInventoryConnectionSpec connection) {
        requestOpen(player, connection, null);
    }

    public static void requestOpen(ServerPlayer player, PlayerInventoryConnectionSpec connection,
                                   String automationEndpointId) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) {
            player.sendSystemMessage(Component.literal("Exchange 尚未就绪"));
            return;
        }
        PlayerExchangeContext requester = context(player);
        Optional<InventoryAccess> session = core.findPlayerInventorySession(
                connection.serverName(), connection.playerName(), requester);
        if (session.isPresent()) {
            PlayerWarehouseAutomationSessions.remember(automationEndpointId, connection, session.orElseThrow());
            open(player, core, connection, session.orElseThrow());
            return;
        }
        if (connection.password().isEmpty()) {
            rememberPending(player, connection, automationEndpointId);
            player.sendSystemMessage(Component.literal(
                    "玩家仓库需要密码，请输入 /exchange player login <password>"));
            return;
        }
        rememberPending(player, connection, automationEndpointId);
        authenticateAndOpen(player, core, connection, connection.password().orElseThrow(), automationEndpointId);
    }

    public static boolean completeLogin(ServerPlayer player, String password) {
        PendingConnection pending = pending(player);
        if (pending == null) {
            player.sendSystemMessage(Component.literal(
                    "没有待验证的玩家仓库，请先使用 /exchange player view <player>@<server>"));
            return false;
        }
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) {
            player.sendSystemMessage(Component.literal("Exchange 尚未就绪"));
            return false;
        }
        authenticateAndOpen(player, core, pending.connection(), password, pending.automationEndpointId());
        return true;
    }

    public static PlayerExchangeContext context(ServerPlayer player) {
        return new PlayerExchangeContext(player.getUUID().toString(), player.getName().getString());
    }

    private static void authenticateAndOpen(ServerPlayer player, TheExchangeCore core,
                                            PlayerInventoryConnectionSpec connection, String password,
                                            String automationEndpointId) {
        player.sendSystemMessage(Component.literal("正在验证玩家仓库: " + connection.redacted()));
        core.authenticatePlayerInventoryAsync(connection.serverName(), connection.playerName(),
                        password, context(player))
                .thenCompose(access -> {
                    PENDING.remove(player.getUUID().toString());
                    PlayerWarehouseAutomationSessions.remember(automationEndpointId, connection, access);
                    return openFuture(core, connection, access);
                })
                .whenComplete((state, error) -> finishOpen(player, core, state, error));
    }

    private static void open(ServerPlayer player, TheExchangeCore core,
                             PlayerInventoryConnectionSpec connection, InventoryAccess access) {
        player.sendSystemMessage(Component.literal("正在加载玩家仓库: " + connection.redacted()));
        openFuture(core, connection, access)
                .whenComplete((state, error) -> finishOpen(player, core, state, error));
    }

    private static CompletableFuture<ExchangeViewState> openFuture(
            TheExchangeCore core, PlayerInventoryConnectionSpec connection, InventoryAccess access) {
        String localName = core.getRuntimeConfig().getDisplayName();
        return isLocal(connection.serverName(), localName)
                ? core.openLocalViewAsync(localName, access)
                : core.openRemoteViewAsync(connection.serverName(), access);
    }

    private static void finishOpen(ServerPlayer player, TheExchangeCore core,
                                   ExchangeViewState state, Throwable error) {
        core.getApi().runOnMainThread(() -> {
            if (error != null) {
                String message = rootMessage(error);
                LOGGER.warn("[Exchange] Failed to open player warehouse {}: {}",
                        state != null ? state.getServerName() : "unknown", message);
                player.sendSystemMessage(Component.literal("打开失败: " + message));
                if (message.contains("玩家不存在") || message.contains("无法解析")
                        || message.contains("仓库不存在")) {
                    PENDING.remove(player.getUUID().toString());
                }
                return;
            }
            if (player.isRemoved()) return;
            player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                    (containerId, inventory, ignored) -> new ExchangeMenu(containerId, inventory, state),
                    Component.literal(state.getTitle(core.getRuntimeConfig().getDisplayName()))));
        });
    }

    private static void rememberPending(ServerPlayer player, PlayerInventoryConnectionSpec connection,
                                        String automationEndpointId) {
        PlayerInventoryConnectionSpec redacted = new PlayerInventoryConnectionSpec(
                connection.playerName(), connection.serverName(), Optional.empty());
        PENDING.put(player.getUUID().toString(),
                new PendingConnection(redacted, automationEndpointId,
                        System.currentTimeMillis() + PENDING_PASSWORD_TTL_MS));
    }

    private static PendingConnection pending(ServerPlayer player) {
        String key = player.getUUID().toString();
        PendingConnection pending = PENDING.get(key);
        if (pending != null && pending.expiresAt() <= System.currentTimeMillis()) {
            PENDING.remove(key, pending);
            return null;
        }
        return pending;
    }

    private static boolean isLocal(String serverName, String localName) {
        return "local".equalsIgnoreCase(serverName) || serverName.equalsIgnoreCase(localName);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    private record PendingConnection(PlayerInventoryConnectionSpec connection,
                                     String automationEndpointId, long expiresAt) {
    }
}
