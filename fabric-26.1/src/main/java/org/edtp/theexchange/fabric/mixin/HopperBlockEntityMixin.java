package org.edtp.theexchange.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.edtp.theexchange.fabric.automation.PlayerWarehouseHopperBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HopperBlockEntity.class)
abstract class HopperBlockEntityMixin {
    @Inject(method = "ejectItems", at = @At("HEAD"), cancellable = true)
    private static void theexchange$pushIntoPlayerWarehouse(
            Level level, BlockPos pos, HopperBlockEntity hopper,
            CallbackInfoReturnable<Boolean> cir) {
        PlayerWarehouseHopperBridge.push(level, pos, hopper).ifPresent(cir::setReturnValue);
    }

    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void theexchange$pullFromPlayerWarehouse(
            Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        PlayerWarehouseHopperBridge.pull(level, hopper).ifPresent(cir::setReturnValue);
    }
}
