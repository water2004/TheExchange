package org.edtp.theexchange.fabric.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.edtp.theexchange.compat.ItemSerializer;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.util.BinaryIO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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
            extraData = writeCanonical(tag);
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

    @Override
    public boolean sameStackKind(NeutralItem a, NeutralItem b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return Objects.equals(a.getItemId(), b.getItemId())
                && Arrays.equals(a.getExtraData(), b.getExtraData());
    }

    @Override
    public int getMaxStackSize(NeutralItem item) {
        Object stackObj = deserialize(item);
        if (stackObj instanceof ItemStack stack) {
            return stack.getMaxStackSize();
        }
        return ItemSerializer.super.getMaxStackSize(item);
    }

    private byte[] writeCanonical(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(10);
        BinaryIO.writeString(out, "");
        writeCompoundSorted(tag, out);
        out.flush();
        return bos.toByteArray();
    }

    private void writeCompoundSorted(CompoundTag tag, DataOutputStream out) throws IOException {
        List<String> keys = new ArrayList<>(tag.keySet());
        keys.sort(Comparator.naturalOrder());
        for (String key : keys) {
            Tag child = tag.get(key);
            if (child == null) continue;
            out.writeByte(child.getId());
            if (child.getId() != 0) {
                BinaryIO.writeString(out, key);
                writeTag(child, out);
            }
        }
        out.writeByte(0);
    }

    private void writeTag(Tag tag, DataOutputStream out) throws IOException {
        if (tag instanceof CompoundTag compound) {
            writeCompoundSorted(compound, out);
            return;
        }
        tag.write(out);
    }
}
