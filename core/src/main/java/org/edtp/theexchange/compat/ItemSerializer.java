package org.edtp.theexchange.compat;

import org.edtp.theexchange.model.NeutralItem;

/**
 * Implemented by each mod loader adapter.
 * Converts between native Minecraft ItemStack and protocol-neutral NeutralItem.
 */
public interface ItemSerializer {

    /**
     * Convert a native ItemStack to the neutral exchange format.
     * @param itemStack The loader-specific ItemStack object (e.g., net.minecraft.world.item.ItemStack)
     */
    NeutralItem serialize(Object itemStack);

    /**
     * Convert a neutral item back to a native ItemStack.
     * If the item is incompatible, returns a barrier block with lore annotation.
     * @param item The neutral item from the exchange protocol
     */
    Object deserialize(NeutralItem item);

    /**
     * Return whether this runtime can materialize the neutral item as its native ItemStack.
     * Implementations must not mutate the item.
     */
    default boolean canDeserialize(NeutralItem item) {
        return item != null && !item.isEmpty() && deserialize(item) != null;
    }

    /**
     * Compare two neutral items by stack-kind identity.
     * Count, server source version and display-only names are intentionally ignored.
     */
    default boolean sameStackKind(NeutralItem a, NeutralItem b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.sameStackKind(b);
    }

    /**
     * Return the native max stack size for the given item.
     * Core falls back to 64 when a loader does not override this.
     */
    default int getMaxStackSize(NeutralItem item) {
        return 64;
    }
}
