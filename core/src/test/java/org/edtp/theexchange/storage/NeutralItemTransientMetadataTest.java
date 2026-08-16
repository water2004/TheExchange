package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.NeutralItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NeutralItemTransientMetadataTest {

    @Test
    void databaseBlobDoesNotPersistMaxStackSize() {
        NeutralItem item = new NeutralItem(
                "minecraft:stone", 12, "Stone", new byte[] {1, 2}, false, "test");
        item.setVersion(7);
        item.setMaxStackSize(16);

        NeutralItem decoded = NeutralItemBlobCodec.decode(NeutralItemBlobCodec.encode(item));

        assertEquals("minecraft:stone", decoded.getItemId());
        assertEquals(12, decoded.getCount());
        assertEquals(7, decoded.getVersion());
        assertEquals(0, decoded.getMaxStackSize());
    }
}
