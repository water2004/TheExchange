package org.edtp.theexchange.fabric.mixin;

import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.storage.ValueInput;
import org.edtp.theexchange.fabric.automation.PlayerWarehouseEndpointCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SignBlockEntity.class)
abstract class SignBlockEntityMixin {
    @Inject(method = "setText", at = @At("RETURN"))
    private void theexchange$invalidateEndpointAfterTextChanged(
            SignText text, boolean front, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            PlayerWarehouseEndpointCache.signTextChanged((SignBlockEntity) (Object) this);
        }
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void theexchange$invalidateEndpointAfterLoad(ValueInput input, CallbackInfo ci) {
        PlayerWarehouseEndpointCache.blockEntityChanged((SignBlockEntity) (Object) this, true);
    }
}
