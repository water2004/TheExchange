package org.edtp.theexchange.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.fabric.container.ExchangeMenu;
import org.edtp.theexchange.model.RemoteServer;
import org.edtp.theexchange.model.ServerStatus;
import org.edtp.theexchange.network.NetworkManager;

import net.minecraft.server.players.NameAndId;
import java.util.List;

public class ExchangeCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("exchange");

        // /exchange server add <name> <address> <port> <password>
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

        // /exchange list (player-facing)
        root.then(Commands.literal("list")
                .executes(ExchangeCommand::listForPlayer));

        // /exchange view <serverName>
        root.then(Commands.literal("view")
                .then(Commands.argument("server", StringArgumentType.string())
                        .executes(ExchangeCommand::viewServer)));

        // /exchange refresh <serverName>
        root.then(Commands.literal("refresh")
                .then(Commands.argument("server", StringArgumentType.string())
                        .executes(ExchangeCommand::refreshServer)));

        // /exchange reload (admin)
        root.then(Commands.literal("reload")
                .executes(ExchangeCommand::reloadConfig));

        // /exchange log export/clear [days]
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
        return src.getServer().getPlayerList().isOp(
                new NameAndId(player.getGameProfile()));
    }

    // ===== Admin commands =====

    private static int addServer(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null && !isAdmin(ctx.getSource())) {
            ctx.getSource().sendFailure(Component.literal("需要管理员权限"));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        String address = StringArgumentType.getString(ctx, "address");
        int port = IntegerArgumentType.getInteger(ctx, "port");
        String password = StringArgumentType.getString(ctx, "password");

        TheExchangeCore.getInstance().getServerRegistry().addServer(name, address, port, password);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "已添加远程服务器: " + name + " (" + address + ":" + port + ")"), true);
        return 1;
    }

    private static int removeServer(CommandContext<CommandSourceStack> ctx) {
        if (!isAdmin(ctx.getSource())) {
            ctx.getSource().sendFailure(Component.literal("需要管理员权限"));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        boolean removed = TheExchangeCore.getInstance().getServerRegistry().removeServer(name);

        if (removed) {
            ctx.getSource().sendSuccess(() -> Component.literal("已移除远程服务器: " + name), true);
        } else {
            ctx.getSource().sendFailure(Component.literal("服务器不存在: " + name));
        }
        return 1;
    }

    private static int listServers(CommandContext<CommandSourceStack> ctx) {
        List<RemoteServer> servers = TheExchangeCore.getInstance().getServerRegistry().getAllServers();
        NetworkManager nm = TheExchangeCore.getInstance().getNetworkManager();

        ctx.getSource().sendSuccess(() -> Component.literal("=== 已配置的远程服务器 ==="), false);
        for (RemoteServer server : servers) {
            ServerStatus status = nm.getStatus(server.getName());
            String statusStr = status == ServerStatus.ONLINE ? "在线" : "离线";
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  " + server.getName() + " - " + server.getAddress() + ":" + server.getPort()
                            + " [" + statusStr + "]"), false);
        }
        if (servers.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  (无)"), false);
        }
        return servers.size();
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        if (!isAdmin(ctx.getSource())) {
            ctx.getSource().sendFailure(Component.literal("需要管理员权限"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("配置热重载暂未实现，请重启服务器"), false);
        return 1;
    }

    // ===== Player commands =====

    private static int listForPlayer(CommandContext<CommandSourceStack> ctx) {
        return listServers(ctx);
    }

    private static int viewServer(CommandContext<CommandSourceStack> ctx) {
        String serverName = StringArgumentType.getString(ctx, "server");
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        TheExchangeCore core = TheExchangeCore.getInstance();
        NetworkManager nm = core.getNetworkManager();
        ServerStatus status = nm.getStatus(serverName);
        boolean online = status == ServerStatus.ONLINE;

        // Sync if online
        if (online) {
            var syncResult = core.getSyncEngine().syncIfNeeded(serverName);
            online = syncResult.isOnline();
        }

        final boolean isOnlineFinal = online;
        MenuProvider provider = new SimpleMenuProvider(
                (containerId, inventory, p) -> new ExchangeMenu(containerId, inventory, serverName, isOnlineFinal),
                Component.literal((isOnlineFinal ? "" : "[离线] ") + serverName + " 的共享空间"));
        player.openMenu(provider);

        if (!isOnlineFinal) {
            player.sendSystemMessage(Component.literal("[离线] 仅可查看缓存数据 — 目标服务器离线"));
        }

        return 1;
    }

    private static int refreshServer(CommandContext<CommandSourceStack> ctx) {
        String serverName = StringArgumentType.getString(ctx, "server");
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        var syncResult = TheExchangeCore.getInstance().getSyncEngine().fullSync(serverName);
        if (syncResult.isOnline()) {
            ctx.getSource().sendSuccess(() -> Component.literal("已刷新 " + serverName), false);
        } else {
            ctx.getSource().sendFailure(Component.literal("目标服务器离线，无法刷新"));
        }
        return 1;
    }

    private static int exportLog(CommandContext<CommandSourceStack> ctx, int days) {
        if (!isAdmin(ctx.getSource())) {
            ctx.getSource().sendFailure(Component.literal("需要管理员权限"));
            return 0;
        }
        long since = System.currentTimeMillis() - (long) days * 24 * 3600 * 1000;
        var logs = TheExchangeCore.getInstance().getOperationLogger().queryLogs(since);
        ctx.getSource().sendSuccess(() -> Component.literal("最近 " + days + " 天的操作记录 (" + logs.size() + " 条):"), false);
        for (var entry : logs) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    entry.timestamp() + " " + entry.opType() + " " + entry.playerName()
                            + " → " + entry.serverName() + " " + entry.itemId()
                            + " x" + entry.quantity() + " " + (entry.success() ? "成功" : entry.failReason())), false);
        }
        return 1;
    }

    private static int clearLog(CommandContext<CommandSourceStack> ctx, int days) {
        if (!isAdmin(ctx.getSource())) {
            ctx.getSource().sendFailure(Component.literal("需要管理员权限"));
            return 0;
        }
        int deleted = TheExchangeCore.getInstance().getOperationLogger().cleanupOldLogs(days);
        ctx.getSource().sendSuccess(() -> Component.literal("已清理 " + deleted + " 条 " + days + " 天前的日志"), true);
        return 1;
    }
}
