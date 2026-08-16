package org.edtp.theexchange.fabric.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.edtp.theexchange.fabric.automation.PlayerWarehouseEndpointCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
abstract class BlockEntityMixin {
    @Inject(method = "setLevel", at = @At("TAIL"))
    private void theexchange$invalidateEndpointAfterLevelAssigned(Level level, CallbackInfo ci) {
        PlayerWarehouseEndpointCache.blockEntityChanged((BlockEntity) (Object) this, true);
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void theexchange$invalidateEndpointBeforeRemoval(CallbackInfo ci) {
        PlayerWarehouseEndpointCache.blockEntityChanged((BlockEntity) (Object) this, false);
    }

    @Inject(method = "setBlockState", at = @At("TAIL"))
    private void theexchange$invalidateEndpointAfterStateChanged(BlockState state, CallbackInfo ci) {
        PlayerWarehouseEndpointCache.blockEntityChanged((BlockEntity) (Object) this, true);
    }
}
