package org.edtp.theexchange.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.model.ExchangeViewState;
import org.edtp.theexchange.model.RemoteServer;
import org.edtp.theexchange.fabric.container.ExchangeMenu;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExchangeCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static TheExchangeCore getCore(CommandContext<CommandSourceStack> ctx) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[Exchange] Mod 尚未初始化，请等待服务器完全启动后再试"));
            return null;
        }
        return core;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("exchange");

        root.then(Commands.literal("server")
                .then(Commands.literal("add")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .then(Commands.argument("address", StringArgumentType.string())
                                        .then(Commands.argument("port", IntegerArgumentType.integer(1, 65535))
                                                .then(Commands.argument("password", StringArgumentType.string())
                                                        .executes(ExchangeCommand::addServer))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ExchangeCommand::removeServer)))
                .then(Commands.literal("list")
                        .executes(ExchangeCommand::listServers)));

        root.then(Commands.literal("list").executes(ExchangeCommand::listForPlayer));
        root.then(Commands.literal("view")
                .then(Commands.argument("server", StringArgumentType.string())
                        .executes(ExchangeCommand::viewServer)));
        root.then(Commands.literal("refresh")
                .then(Commands.argument("server", StringArgumentType.string())
                        .executes(ExchangeCommand::refreshServer)));
        root.then(Commands.literal("reload").executes(ExchangeCommand::reloadConfig));

        root.then(Commands.literal("log")
                .then(Commands.literal("export")
                        .executes(ctx -> exportLog(ctx, 30))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
                                .executes(ctx -> exportLog(ctx, IntegerArgumentType.getInteger(ctx, "days")))))
                .then(Commands.literal("clear")
                        .executes(ctx -> clearLog(ctx, 30))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
                                .executes(ctx -> clearLog(ctx, IntegerArgumentType.getInteger(ctx, "days"))))));

        dispatcher.register(root);
    }

    private static boolean isAdmin(CommandSourceStack src) {
        if (src.getServer().isSingleplayer()) return true;
        var player = src.getPlayer();
        if (player == null) return false;
        return src.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
    }

    private static int addServer(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayer();
            if (player != null && !isAdmin(ctx.getSource())) {
                ctx.getSource().sendFailure(Component.literal("需要管理员权限"));
                return 0;
            }
            TheExchangeCore core = getCore(ctx);
            if (core == null) return 0;

            String name = StringArgumentType.getString(ctx, "name");
            String address = StringArgumentType.getString(ctx, "address");
            int port = IntegerArgumentType.getInteger(ctx, "port");
            String password = StringArgumentType.getString(ctx, "password");

            core.executeCore(() -> core.getServerRegistry().addServer(name, address, port, password))
                    .whenComplete((ignored, error) -> core.getApi().runOnMainThread(() -> {
                        if (error != null) {
                            LOGGER.error("[Exchange] Error in addServer", error);
                            ctx.getSource().sendFailure(Component.literal(
                                    "添加失败: " + rootMessage(error)));
                        } else {
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "已添加远程服务器: " + name + " (" + address + ":" + port + ")"), true);
                        }
                    }));
            return 1;
        } catch (Exception e) {
            LOGGER.error("[Exchange] Error in addServer", e);
            ctx.getSource().sendFailure(Component.literal("内部错误: " + e.getMessage()));
            return 0;
        }
    }

    private static int removeServer(CommandContext<CommandSourceStack> ctx) {
        try {
            if (!isAdmin(ctx.getSource())) {
                ctx.getSource().sendFailure(Component.literal("需要管理员权限"));
                return 0;
            }
            TheExchangeCore core = getCore(ctx);
            if (core == null) return 0;

            String name = StringArgumentType.getString(ctx, "name");
            core.submit(() -> core.getServerRegistry().removeServer(name))
                    .whenComplete((removed, error) -> core.getApi().runOnMainThread(() -> {
                        if (error != null) {
                            LOGGER.error("[Exchange] Error in removeServer", error);
                            ctx.getSource().sendFailure(Component.literal(
                                    "移除失败: " + rootMessage(error)));
                        } else if (Boolean.TRUE.equals(removed)) {
                            ctx.getSource().sendSuccess(() -> Component.literal("已移除: " + name), true);
                        } else {
                            ctx.getSource().sendFailure(Component.literal("服务器不存在: " + name));
                        }
                    }));
            return 1;
        } catch (Exception e) {
            LOGGER.error("[Exchange] Error in removeServer", e);
            ctx.getSource().sendFailure(Component.literal("内部错误: " + e.getMessage()));
            return 0;
        }
    }

    private static int listServers(CommandContext<CommandSourceStack> ctx) {
        try {
            TheExchangeCore core = getCore(ctx);
            if (core == null) return 0;
            core.submit(() -> new ServerListSnapshot(
                            core.getServerRegistry().getAllServers(),
                            core.getServerRegistry().isNetworkAvailable(),
                            core.getApi().getServerName(),
                            serverStatuses(core)))
                    .whenComplete((snapshot, error) -> core.getApi().runOnMainThread(() -> {
                        if (error != null) {
                            LOGGER.error("[Exchange] Error in listServers", error);
                            ctx.getSource().sendFailure(Component.literal(
                                    "读取服务器列表失败: " + rootMessage(error)));
                            return;
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal("=== 共享空间服务器列表 ==="), false);
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "  [本服] " + snapshot.localName() + " - 使用 /exchange view local 打开"), false);
                        for (RemoteServer server : snapshot.servers()) {
                            String statusStr = !snapshot.networkAvailable() ? "离线 (网络未启用)"
                                    : snapshot.statusByName().getOrDefault(server.getName(), "离线");
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "  " + server.getName() + " - " + server.getAddress()
                                            + ":" + server.getPort() + " [" + statusStr + "]"), false);
                        }
                        if (snapshot.servers().isEmpty()) {
                            ctx.getSource().sendSuccess(() -> Component.literal("  (无远程服务器)"), false);
                        }
                    }));
            return 1;
        } catch (Exception e) {
            LOGGER.error("[Exchange] Error in listServers", e);
            ctx.getSource().sendFailure(Component.literal("内部错误: " + e.getMessage()));
            return 0;
        }
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("请使用 /exchange reload 重启以重载配置"), false);
        return 1;
    }

    private static int listForPlayer(CommandContext<CommandSourceStack> ctx) {
        return listServers(ctx);
    }

    private static int viewServer(CommandContext<CommandSourceStack> ctx) {
        try {
            String serverName = StringArgumentType.getString(ctx, "server");
            ServerPlayer player = ctx.getSource().getPlayer();
            if (player == null) return 0;

            TheExchangeCore core = getCore(ctx);
            if (core == null) return 0;

            ctx.getSource().sendSuccess(() -> Component.literal("正在加载共享空间: " + serverName), false);
            core.submit(() -> {
                        String localName = core.getApi().getServerName();
                        return "local".equalsIgnoreCase(serverName)
                                || serverName.equalsIgnoreCase(localName)
                                ? core.openLocalViewAsync(localName)
                                : core.openRemoteViewAsync(serverName);
                    })
                    .thenCompose(future -> future)
                    .whenComplete((state, error) -> core.getApi().runOnMainThread(() -> {
                        if (error != null) {
                            LOGGER.error("[Exchange] Error in viewServer", error);
                            player.sendSystemMessage(Component.literal("打开失败: " + rootMessage(error)));
                            return;
                        }
                        if (player.isRemoved()) return;
                        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                                (containerId, inventory, p) -> new ExchangeMenu(
                                        containerId, inventory, state),
                                Component.literal(state.getTitle(core.getApi().getServerName()))));

                        if (!state.isOnline() && !state.isLocal()) {
                            player.sendSystemMessage(Component.literal("[离线] 仅可查看缓存数据 - 目标服务器离线"));
                        }
                    }));
            return 1;
        } catch (Exception e) {
            LOGGER.error("[Exchange] Error in viewServer", e);
            ctx.getSource().sendFailure(Component.literal("内部错误: " + e.getMessage()));
            return 0;
        }
    }

    private static int refreshServer(CommandContext<CommandSourceStack> ctx) {
        try {
            String serverName = StringArgumentType.getString(ctx, "server");
            ServerPlayer player = ctx.getSource().getPlayer();
            if (player == null) return 0;

            TheExchangeCore core = getCore(ctx);
            if (core == null) return 0;

            ctx.getSource().sendSuccess(() -> Component.literal("正在刷新 " + serverName), false);
            core.refreshRemoteViewAsync(serverName)
                    .whenComplete((ignored, error) -> core.getApi().runOnMainThread(() -> {
                        if (error != null) {
                            LOGGER.error("[Exchange] Error in refreshServer", error);
                            ctx.getSource().sendFailure(Component.literal(
                                    "刷新失败: " + rootMessage(error)));
                        } else {
                            ctx.getSource().sendSuccess(() -> Component.literal("已刷新 " + serverName), false);
                            core.getApi().refreshRemoteInventoryView(serverName);
                        }
                    }));
            return 1;
        } catch (Exception e) {
            LOGGER.error("[Exchange] Error in refreshServer", e);
            ctx.getSource().sendFailure(Component.literal("内部错误: " + e.getMessage()));
            return 0;
        }
    }

    private static int exportLog(CommandContext<CommandSourceStack> ctx, int days) {
        try {
            TheExchangeCore core = getCore(ctx);
            if (core == null) return 0;

            long since = System.currentTimeMillis() - (long) days * 24 * 3600 * 1000;
            core.submit(() -> core.getOperationLogger().queryLogs(since))
                    .whenComplete((logs, error) -> core.getApi().runOnMainThread(() -> {
                        if (error != null) {
                            LOGGER.error("[Exchange] Error in exportLog", error);
                            ctx.getSource().sendFailure(Component.literal(
                                    "导出失败: " + rootMessage(error)));
                            return;
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "最近 " + days + " 天的记录 (" + logs.size() + " 条):"), false);
                        for (var entry : logs) {
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    entry.timestamp() + " " + entry.opType() + " "
                                            + entry.playerName() + " -> " + entry.serverName()
                                            + " " + entry.itemId() + " x" + entry.quantity()
                                            + " " + (entry.success() ? "成功" : entry.failReason())), false);
                        }
                    }));
            return 1;
        } catch (Exception e) {
            LOGGER.error("[Exchange] Error in exportLog", e);
            ctx.getSource().sendFailure(Component.literal("内部错误: " + e.getMessage()));
            return 0;
        }
    }

    private static int clearLog(CommandContext<CommandSourceStack> ctx, int days) {
        try {
            TheExchangeCore core = getCore(ctx);
            if (core == null) return 0;

            core.submit(() -> core.getOperationLogger().cleanupOldLogs(days))
                    .whenComplete((deleted, error) -> core.getApi().runOnMainThread(() -> {
                        if (error != null) {
                            LOGGER.error("[Exchange] Error in clearLog", error);
                            ctx.getSource().sendFailure(Component.literal(
                                    "清理失败: " + rootMessage(error)));
                        } else {
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "已清理 " + deleted + " 条 " + days + " 天前的日志"), true);
                        }
                    }));
            return 1;
        } catch (Exception e) {
            LOGGER.error("[Exchange] Error in clearLog", e);
            ctx.getSource().sendFailure(Component.literal("内部错误: " + e.getMessage()));
            return 0;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    private static Map<String, String> serverStatuses(TheExchangeCore core) {
        Map<String, String> statuses = new LinkedHashMap<>();
        for (RemoteServer server : core.getServerRegistry().getAllServers()) {
            statuses.put(server.getName(),
                    core.getServerRegistry().getStatus(server.getName()).name().equals("ONLINE")
                            ? "在线" : "离线");
        }
        return statuses;
    }

    private record ServerListSnapshot(List<RemoteServer> servers,
                                      boolean networkAvailable,
                                      String localName,
                                      Map<String, String> statusByName) {}
}
