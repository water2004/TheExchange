package org.edtp.theexchange.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

// Diff vs 26.1: No Permissions API (Permissions.COMMANDS_ADMIN).
// Use manual OP check instead: player != null ? server.getPlayerList().isOp(player.getGameProfile()) : true

public class ExchangeCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("exchange");
        // TODO: register subcommands
        dispatcher.register(root);
    }
}
