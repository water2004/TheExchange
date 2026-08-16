package org.edtp.theexchange.fabric.automation;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.edtp.theexchange.fabric.block.AttachedEnderChestSign;
import org.edtp.theexchange.model.PlayerInventoryConnectionSpec;

import java.util.IdentityHashMap;
import java.util.Map;

/** Main-thread cache for signed warehouse endpoints used by hopper automation. */
public final class PlayerWarehouseEndpointCache {
    private static final int MAX_ENTRIES_PER_LEVEL = 65_536;
    private static final Object NOT_MAPPED = new Object();
    private static final Map<Level, Long2ObjectOpenHashMap<Object>> LEVEL_CACHES =
            new IdentityHashMap<>();

    private PlayerWarehouseEndpointCache() {
    }

    /** Returns null when the position is not a signed ender chest. */
    public static PlayerInventoryConnectionSpec find(Level level, BlockPos chestPos) {
        return find(level, chestPos.asLong());
    }

    /** Primitive-key overload used by the normal-hopper hot path. */
    public static PlayerInventoryConnectionSpec find(Level level, long key) {
        Long2ObjectOpenHashMap<Object> cache = LEVEL_CACHES.get(level);
        if (cache == null) {
            cache = new Long2ObjectOpenHashMap<>();
            LEVEL_CACHES.put(level, cache);
        }
        Object cached = cache.get(key);
        if (cached != null) {
            return cached == NOT_MAPPED ? null : (PlayerInventoryConnectionSpec) cached;
        }

        BlockPos chestPos = BlockPos.of(key);
        PlayerInventoryConnectionSpec connection = level.getBlockState(chestPos).is(Blocks.ENDER_CHEST)
                ? AttachedEnderChestSign.find(level, chestPos).orElse(null)
                : null;
        if (cache.size() >= MAX_ENTRIES_PER_LEVEL) {
            cache.remove(cache.keySet().iterator().nextLong());
        }
        cache.put(key, connection == null ? NOT_MAPPED : connection);
        return connection;
    }

    /** Invalidates endpoints affected by a sign or ender-chest block entity lifecycle change. */
    public static void blockEntityChanged(BlockEntity blockEntity, boolean wakeHoppers) {
        Level level = blockEntity.getLevel();
        if (level == null || level.isClientSide()) return;
        if (blockEntity instanceof SignBlockEntity) {
            invalidateAroundSign(level, blockEntity.getBlockPos(), wakeHoppers);
        } else if (blockEntity instanceof EnderChestBlockEntity) {
            invalidate(level, blockEntity.getBlockPos());
        }
    }

    /** Invalidates the edited sign and wakes hoppers adjacent to any neighboring ender chest. */
    public static void signTextChanged(SignBlockEntity sign) {
        Level level = sign.getLevel();
        if (level == null || level.isClientSide()) return;
        invalidateAroundSign(level, sign.getBlockPos(), true);
    }

    public static void clear() {
        LEVEL_CACHES.clear();
    }

    private static void invalidateAroundSign(Level level, BlockPos signPos, boolean wakeHoppers) {
        for (Direction direction : Direction.values()) {
            BlockPos chestPos = signPos.relative(direction);
            boolean wasCached = invalidate(level, chestPos);
            if (wakeHoppers && level.isLoaded(chestPos)
                    && (wasCached || level.getBlockState(chestPos).is(Blocks.ENDER_CHEST))) {
                level.updateNeighborsAt(chestPos, Blocks.ENDER_CHEST, null);
            }
        }
    }

    private static boolean invalidate(Level level, BlockPos chestPos) {
        Long2ObjectOpenHashMap<Object> cache = LEVEL_CACHES.get(level);
        return cache != null && cache.remove(chestPos.asLong()) != null;
    }
}
