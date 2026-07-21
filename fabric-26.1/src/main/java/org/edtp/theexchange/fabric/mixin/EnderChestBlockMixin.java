package org.edtp.theexchange.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.edtp.theexchange.fabric.block.AttachedEnderChestSign;
import org.edtp.theexchange.fabric.automation.PlayerWarehouseEndpointId;
import org.edtp.theexchange.fabric.player.PlayerWarehouseAccessCoordinator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EnderChestBlock.class)
abstract class EnderChestBlockMixin {
    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void theexchange$openAttachedWarehouse(BlockState state, Level level, BlockPos pos,
                                                   Player player, BlockHitResult hit,
                                                   CallbackInfoReturnable<InteractionResult> cir) {
        var connection = AttachedEnderChestSign.find(level, pos);
        if (connection.isEmpty()) return;
        cir.setReturnValue(InteractionResult.SUCCESS);
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer
                && !level.getBlockState(pos.above()).isRedstoneConductor(level, pos.above())) {
            PlayerWarehouseAccessCoordinator.requestOpen(serverPlayer, connection.orElseThrow(),
                    PlayerWarehouseEndpointId.forBlock(level, pos));
        }
    }
}
