package org.edtp.theexchange.compat;

import org.edtp.theexchange.model.NeutralItem;

/**
 * Checks whether a NeutralItem can be resolved in the current Minecraft version.
 * The receiver (authoritative server) is the final arbiter of compatibility.
 */
public class CompatibilityChecker {

    private final ItemSerializer serializer;

    public CompatibilityChecker(ItemSerializer serializer) {
        this.serializer = serializer;
    }

    /**
     * Check and mark compatibility on a NeutralItem.
     * Attempts deserialization; if it fails or the item ID is unknown,
     * marks the item as incompatible.
     */
    public NeutralItem checkAndMark(NeutralItem item) {
        if (item == null || item.isEmpty()) return item;
        item.setIncompatible(!serializer.canDeserialize(item));
        return item;
    }

}
