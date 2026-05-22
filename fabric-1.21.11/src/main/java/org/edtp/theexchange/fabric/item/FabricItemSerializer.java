package org.edtp.theexchange.fabric.item;

import net.minecraft.world.item.ItemStack;
import org.edtp.theexchange.compat.ItemSerializer;
import org.edtp.theexchange.model.NeutralItem;

// Diff vs 26.1: 1.21.11 uses NBT tag system (CompoundTag + NbtIo),
// NOT Data Components CODEC. serialize() writes via NbtIo.write(),
// deserialize() reads via NbtIo.read(). canDeserialize() uses
// BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(id)).

public class FabricItemSerializer implements ItemSerializer {
    @Override public NeutralItem serialize(Object itemStack) { return null; }
    @Override public Object deserialize(NeutralItem item) { return ItemStack.EMPTY; }
    @Override public boolean canDeserialize(NeutralItem item) { return false; }
    @Override public int getMaxStackSize(NeutralItem item) { return 64; }
}
