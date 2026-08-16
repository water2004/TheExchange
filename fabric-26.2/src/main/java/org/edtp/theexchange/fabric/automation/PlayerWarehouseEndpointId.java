package org.edtp.theexchange.fabric.automation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public final class PlayerWarehouseEndpointId {
    private PlayerWarehouseEndpointId() {
    }

    public static String forBlock(Level level, BlockPos pos) {
        return level.dimension().identifier() + ":" + pos.asLong();
    }
}
