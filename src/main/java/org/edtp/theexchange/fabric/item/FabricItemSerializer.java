package org.edtp.theexchange.fabric.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.edtp.theexchange.compat.ItemSerializer;
import org.edtp.theexchange.model.NeutralItem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * Fabric-specific ItemStack ↔ NeutralItem serialization.
 * Uses Minecraft's built-in CODEC system for NBT round-tripping.
 */
public class FabricItemSerializer implements ItemSerializer {

    @Override
    public NeutralItem serialize(Object itemStack) {
        if (!(itemStack instanceof ItemStack stack)) return null;
        if (stack.isEmpty()) return null;

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String displayName = stack.getHoverName().getString();
        byte[] extraData = null;

        try {
            CompoundTag tag = (CompoundTag) ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, stack)
                    .getOrThrow();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            NbtIo.write(tag, dos);
            dos.flush();
            extraData = bos.toByteArray();
        } catch (Exception e) {
            extraData = new byte[0];
        }

        return new NeutralItem(itemId, stack.getCount(), displayName,
                extraData, false, "26.1.2");
    }

    @Override
    public Object deserialize(NeutralItem item) {
        if (item == null || item.isEmpty()) return ItemStack.EMPTY;

        try {
            if (item.getExtraData() != null && item.getExtraData().length > 0) {
                ByteArrayInputStream bis = new ByteArrayInputStream(item.getExtraData());
                DataInputStream dis = new DataInputStream(bis);
                CompoundTag tag = NbtIo.read(dis);
                ItemStack stack = ItemStack.CODEC.parse(NbtOps.INSTANCE, tag)
                        .getOrThrow();
                stack.setCount(item.getCount());
                return stack;
            }
        } catch (Exception ignored) {
        }

        // Try to find the item by ID
        Identifier rl = Identifier.tryParse(item.getItemId());
        if (rl != null) {
            var itemHolder = BuiltInRegistries.ITEM.get(rl);
            if (itemHolder.isPresent() && itemHolder.get().value() != Items.AIR) {
                ItemStack stack = new ItemStack(itemHolder.get().value(), item.getCount());
                if (item.getDisplayName() != null && !item.getDisplayName().isEmpty()) {
                    stack.set(DataComponents.CUSTOM_NAME, Component.literal(item.getDisplayName()));
                }
                return stack;
            }
        }

        // Incompatible: return barrier block with lore
        item.setIncompatible(true);
        ItemStack barrier = new ItemStack(Items.BARRIER, 1);
        barrier.set(DataComponents.CUSTOM_NAME, Component.literal("不兼容 - " + item.getItemId()));
        return barrier;
    }
}
