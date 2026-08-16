package org.edtp.theexchange.fabric.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import org.edtp.theexchange.model.PlayerInventoryConnectionSpec;

import java.util.Optional;

/** Locates and parses a sign physically supported by an ender chest. */
public final class AttachedEnderChestSign {
    private AttachedEnderChestSign() {
    }

    public static Optional<PlayerInventoryConnectionSpec> find(Level level, BlockPos chestPos) {
        for (Direction direction : Direction.values()) {
            BlockPos signPos = chestPos.relative(direction);
            if (!(level.getBlockEntity(signPos) instanceof SignBlockEntity sign)) continue;
            BlockState state = level.getBlockState(signPos);
            if (!isSupportedByChest(state, signPos, chestPos)) continue;
            Optional<PlayerInventoryConnectionSpec> front = parse(sign.getFrontText());
            if (front.isPresent()) return front;
            Optional<PlayerInventoryConnectionSpec> back = parse(sign.getBackText());
            if (back.isPresent()) return back;
        }
        return Optional.empty();
    }

    private static boolean isSupportedByChest(BlockState state, BlockPos signPos, BlockPos chestPos) {
        if (state.getBlock() instanceof WallSignBlock) {
            return signPos.relative(state.getValue(WallSignBlock.FACING).getOpposite()).equals(chestPos);
        }
        if (state.getBlock() instanceof StandingSignBlock) {
            return signPos.below().equals(chestPos);
        }
        if (state.getBlock() instanceof CeilingHangingSignBlock) {
            return signPos.above().equals(chestPos);
        }
        if (state.getBlock() instanceof WallHangingSignBlock) {
            Direction facing = state.getValue(WallHangingSignBlock.FACING);
            return signPos.relative(facing.getClockWise()).equals(chestPos)
                    || signPos.relative(facing.getCounterClockWise()).equals(chestPos);
        }
        return false;
    }

    private static Optional<PlayerInventoryConnectionSpec> parse(SignText text) {
        for (int line = 0; line < 4; line++) {
            String value = text.getMessage(line, false).getString();
            try {
                return Optional.of(PlayerInventoryConnectionSpec.parse(value));
            } catch (IllegalArgumentException ignored) {
                // The remaining sign lines may contain the connection.
            }
        }
        return Optional.empty();
    }
}
