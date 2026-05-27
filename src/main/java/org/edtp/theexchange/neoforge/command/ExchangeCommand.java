package org.edtp.theexchange.neoforge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.edtp.theexchange.TheExchangeCore;
import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.neoforge.container.ExchangeMenu;
import org.edtp.theexchange.model.RemoteServer;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ExchangeCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static TheExchangeCore getCore(CommandContext<CommandSourceStack> ctx) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) {
            ctx.getSource().sendFailure(Component.literal("[Exchange] Mod 尚未初始化，请等待服务器完全启动后再试"));
            return null;
        }
        return core;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("exchange");

        root.then(Commands.literal("config")
                .requires(ExchangeCommand::isAdmin)
                .then(Commands.literal("show").executes(ExchangeCommand::configShow))
                .then(Commands.literal("get")
                        .then(Commands.argument("path", StringArgumentType.string())
                                .suggests(ExchangeCommand::suggestReadableConfigPaths)
                                .executes(ExchangeCommand::configGet)))
                .then(Commands.literal("set")
                        .then(Commands.argument("path", StringArgumentType.string())
                                .suggests(ExchangeCommand::suggestWritableConfigPaths)
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .suggests(ExchangeCommand::suggestConfigValue)
                                        .executes(ExchangeCommand::configSet))))
                .then(Commands.literal("remote")
                        .then(Commands.literal("list").executes(ExchangeCommand::configRemoteList))
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .then(Commands.argument("address", StringArgumentType.string())
                                                .then(Commands.argument("port", IntegerArgumentType.integer(1, 65535))
                                                        .then(Commands.argument("password", StringArgumentType.string())
                                                                .executes(ExchangeCommand::configRemoteAdd))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .suggests(ExchangeCommand::suggestConfigRemoteNames)
                                        .executes(ExchangeCommand::configRemoteRemove))))
                .then(Commands.literal("reload").executes(ExchangeCommand::configReload)));

        root.then(Commands.literal("server")
                .then(Commands.literal("list").executes(ExchangeCommand::listServers)));

        root.then(Commands.literal("list").executes(ExchangeCommand::listServers));
        root.then(Commands.literal("view")
                .then(Commands.argument("server", StringArgumentType.string())
                        .suggests(ExchangeCommand::suggestViewServers)
                        .executes(ExchangeCommand::viewServer)));
        root.then(Commands.literal("refresh")
                .then(Commands.argument("server", StringArgumentType.string())
                        .suggests(ExchangeCommand::suggestRemoteServers)
                        .executes(ExchangeCommand::refreshServer)));
        root.then(Commands.literal("reload")
                .requires(ExchangeCommand::isAdmin)
                .executes(ExchangeCommand::configReload));

        root.then(Commands.literal("log")
                .then(Commands.literal("export")
                        .executes(ctx -> exportLog(ctx, 30))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
                                .executes(ctx -> exportLog(ctx, IntegerArgumentType.getInteger(ctx, "days")))))
                .then(Commands.literal("clear")
                        .requires(ExchangeCommand::isAdmin)
                        .executes(ctx -> clearLog(ctx, 30))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
                                .executes(ctx -> clearLog(ctx, IntegerArgumentType.getInteger(ctx, "days"))))));

        dispatcher.register(root);
    }

    private static boolean isAdmin(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
    }

    private static CompletableFuture<Suggestions> suggestReadableConfigPaths(CommandContext<CommandSourceStack> ctx,
                                                                             SuggestionsBuilder builder) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) {
            return builder.buildFuture();
        }
        return SharedSuggestionProvider.suggest(core.getConfigManager().readablePaths(), builder);
    }

    private static CompletableFuture<Suggestions> suggestWritableConfigPaths(CommandContext<CommandSourceStack> ctx,
                                                                             SuggestionsBuilder builder) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) {
            return builder.buildFuture();
        }
        return SharedSuggestionProvider.suggest(core.getConfigManager().writablePaths(), builder);
    }

    private static CompletableFuture<Suggestions> suggestConfigValue(CommandContext<CommandSourceStack> ctx,
                                                                     SuggestionsBuilder builder) {
        String path = StringArgumentType.getString(ctx, "path");
        if ("network.inbound_enabled".equals(path)) {
            return SharedSuggestionProvider.suggest(List.of("true", "false"), builder);
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestConfigRemoteNames(CommandContext<CommandSourceStack> ctx,
                                                                           SuggestionsBuilder builder) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) {
            return builder.buildFuture();
        }
        try {
            return SharedSuggestionProvider.suggest(
                    core.getConfigManager().listRemoteServers().stream()
                            .map(ExchangeAPI.RemoteServerConfig::getName),
                    builder);
        } catch (Exception ignored) {
            return builder.buildFuture();
        }
    }

    private static CompletableFuture<Suggestions> suggestViewServers(CommandContext<CommandSourceStack> ctx,
                                                                     SuggestionsBuilder builder) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add("local");
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core != null && core.isInitialized()) {
            for (RemoteServer server : core.getServerRegistry().getAllServers()) {
                names.add(server.getName());
            }
        }
        return SharedSuggestionProvider.suggest(names, builder);
    }

    private static CompletableFuture<Suggestions> suggestRemoteServers(CommandContext<CommandSourceStack> ctx,
                                                                       SuggestionsBuilder builder) {
        TheExchangeCore core = TheExchangeCore.getInstance();
        if (core == null || !core.isInitialized()) {
            return builder.buildFuture();
        }
        return SharedSuggestionProvider.suggest(
                core.getServerRegistry().getAllServers().stream().map(RemoteServer::getName),
                builder);
    }

    private static int configShow(CommandContext<CommandSourceStack> ctx) {
        TheExchangeCore core = getCore(ctx);
        if (core == null) return 0;
        try {
            String json = core.getConfigManager().show();
            for (String line : json.split("\\R")) {
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            }
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("读取配置失败: " + rootMessage(e)));
            return 0;
        }
    }

    private static int configGet(CommandContext<CommandSourceStack> ctx) {
        TheExchangeCore core = getCore(ctx);
        if (core == null) return 0;
        String path = StringArgumentType.getString(ctx, "path");
        try {
            String value = core.getConfigManager().get(path);
            ctx.getSource().sendSuccess(() -> Component.literal(path + " = " + value), false);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("读取配置失败: " + rootMessage(e)));
            return 0;
        }
    }

    private static int configSet(CommandContext<CommandSourceStack> ctx) {
        TheExchangeCore core = getCore(ctx);
        if (core == null) return 0;
        String path = StringArgumentType.getString(ctx, "path");
        String value = StringArgumentType.getString(ctx, "value");
        try {
            core.getConfigManager().set(path, value);
            ctx.getSource().sendSuccess(() -> Component.literal("已写入配置文件，执行 /exchange config reload 后生效"), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("写入配置失败: " + rootMessage(e)));
            return 0;
        }
    }

    private static int configRemoteAdd(CommandContext<CommandSourceStack> ctx) {
        TheExchangeCore core = getCore(ctx);
        if (core == null) return 0;
        String name = StringArgumentType.getString(ctx, "name");
        String address = StringArgumentType.getString(ctx, "address");
        int port = IntegerArgumentType.getInteger(ctx, "port");
        String password = StringArgumentType.getString(ctx, "password");
        try {
            core.getConfigManager().addRemote(name, address, port, password);
            ctx.getSource().sendSuccess(() -> Component.literal("已写入远端配置，执行 /exchange config reload 后生效: " + name), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("写入远端配置失败: " + rootMessage(e)));
            return 0;
        }
    }

    private static int configRemoteRemove(CommandContext<CommandSourceStack> ctx) {
        TheExchangeCore core = getCore(ctx);
        if (core == null) return 0;
        String name = StringArgumentType.getString(ctx, "name");
        try {
            boolean removed = core.getConfigManager().removeRemote(name);
            if (removed) {
                ctx.getSource().sendSuccess(() -> Component.literal("已从配置文件移除，执行 /exchange config reload 后生效: " + name), true);
                return 1;
            }
            ctx.getSource().sendFailure(Component.literal("远端服务器不存在: " + name));
            return 0;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("移除远端配置失败: " + rootMessage(e)));
            return 0;
        }
    }

    private static int configRemoteList(CommandContext<CommandSourceStack> ctx) {
        TheExchangeCore core = getCore(ctx);
        if (core == null) return 0;
        try {
            List<ExchangeAPI.RemoteServerConfig> remotes = core.getConfigManager().listRemoteServers();
            ctx.getSource().sendSuccess(() -> Component.literal("=== 配置文件中的远端服务器 ==="), false);
            for (ExchangeAPI.RemoteServerConfig remote : remotes) {
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "  " + remote.getName() + " - " + remote.getAddress() + ":" + remote.getPort()), false);
            }
            if (remotes.isEmpty()) {
                ctx.getSource().sendSuccess(() -> Component.literal("  (无远端服务器)"), false);
            }
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("读取远端配置失败: " + rootMessage(e)));
            return 0;
        }
    }

    private static int configReload(CommandContext<CommandSourceStack> ctx) {
        TheExchangeCore core = getCore(ctx);
        if (core == null) return 0;
        ctx.getSource().sendSuccess(() -> Component.literal("正在重载 Exchange 配置..."), false);
        core.reloadConfigAsync().whenComplete((config, error) -> core.getApi().runOnMainThread(() -> {
            if (error != null) {
                LOGGER.error("[Exchange] Error in config reload", error);
                ctx.getSource().sendFailure(Component.literal("重载失败: " + rootMessage(error)));
                return;
            }
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "重载完成: port=" + config.getPort()
                            + ", inbound=" + config.getNetwork().isInboundEnabled()
                            + ", remotes=" + config.getRemoteServers().size()), true);
        }));
        return 1;
    }

    private static int listServers(CommandContext<CommandSourceStack> ctx) {
        try {
            TheExchangeCore core = getCore(ctx);
            if (core == null) return 0;
            core.submit(() -> new ServerListSnapshot(
                            core.getServerRegistry().getAllServers(),
                            core.getServerRegistry().isNetworkAvailable(),
                            core.getRuntimeConfig().getDisplayName(),
                            serverStatuses(core)))
                    .whenComplete((snapshot, error) -> core.getApi().runOnMainThread(() -> {
                        if (error != null) {
                            LOGGER.error("[Exchange] Error in listServers", error);
                            ctx.getSource().sendFailure(Component.literal("读取服务器列表失败: " + rootMessage(error)));
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

    private static int viewServer(CommandContext<CommandSourceStack> ctx) {
        try {
            String serverName = StringArgumentType.getString(ctx, "server");
            ServerPlayer player = ctx.getSource().getPlayer();
            if (player == null) return 0;

            TheExchangeCore core = getCore(ctx);
            if (core == null) return 0;

            ctx.getSource().sendSuccess(() -> Component.literal("正在加载共享空间: " + serverName), false);
            core.submit(() -> {
                        String localName = core.getRuntimeConfig().getDisplayName();
                        return "local".equalsIgnoreCase(serverName) || serverName.equalsIgnoreCase(localName)
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
                                (containerId, inventory, p) -> new ExchangeMenu(containerId, inventory, state),
                                Component.literal(state.getTitle(core.getRuntimeConfig().getDisplayName()))));
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
                            ctx.getSource().sendFailure(Component.literal("刷新失败: " + rootMessage(error)));
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
                            ctx.getSource().sendFailure(Component.literal("导出失败: " + rootMessage(error)));
                            return;
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal("最近 " + days + " 天的记录 (" + logs.size() + " 条):"), false);
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
                            ctx.getSource().sendFailure(Component.literal("清理失败: " + rootMessage(error)));
                        } else {
                            ctx.getSource().sendSuccess(() -> Component.literal("已清理 " + deleted + " 条 " + days + " 天前的日志"), true);
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
                    core.getServerRegistry().getStatus(server.getName()).name().equals("ONLINE") ? "在线" : "离线");
        }
        return statuses;
    }

    private record ServerListSnapshot(List<RemoteServer> servers,
                                      boolean networkAvailable,
                                      String localName,
                                      Map<String, String> statusByName) {}
}
