package org.edtp.theexchange.fabric.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Arrays;

/**
 * Fabric-specific ItemStack ↔ NeutralItem serialization.
 * Uses Minecraft's built-in CODEC system for NBT round-tripping.
 */
public class FabricItemSerializer implements ItemSerializer {

    private final String sourceVersion;

    public FabricItemSerializer(String sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

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
            tag.remove("count");
            extraData = writeCanonical(tag);
        } catch (Exception e) {
            extraData = new byte[0];
        }

        return new NeutralItem(itemId, stack.getCount(), displayName,
                extraData, false, sourceVersion);
    }

    @Override
    public boolean canDeserialize(NeutralItem item) {
        if (item == null || item.isEmpty()) return false;
        try {
            Identifier id = Identifier.tryParse(item.getItemId());
            if (id == null) {
                debugCanDeserializeFailure(item, "BAD_ID", null);
                return false;
            }
            var itemHolder = BuiltInRegistries.ITEM.get(id);
            if (itemHolder.isEmpty() || itemHolder.get().value() == Items.AIR) {
                debugCanDeserializeFailure(item, "UNKNOWN_ITEM", null);
                return false;
            }
            if (item.getExtraData() == null || item.getExtraData().length == 0) {
                return true;
            }
            ByteArrayInputStream bis = new ByteArrayInputStream(item.getExtraData());
            DataInputStream dis = new DataInputStream(bis);
            CompoundTag tag = NbtIo.read(dis);
            tag.putInt("count", Math.max(1, item.getCount()));
            ItemStack.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
            return true;
        } catch (Exception e) {
            debugCanDeserializeFailure(item, "NBT_OR_CODEC", e);
            return false;
        }
    }

    @Override
    public Object deserialize(NeutralItem item) {
        if (item == null || item.isEmpty()) return ItemStack.EMPTY;

        try {
            if (item.getExtraData() != null && item.getExtraData().length > 0) {
                ByteArrayInputStream bis = new ByteArrayInputStream(item.getExtraData());
                DataInputStream dis = new DataInputStream(bis);
                CompoundTag tag = NbtIo.read(dis);
                tag.putInt("count", Math.max(1, item.getCount()));
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
    public int getMaxStackSize(NeutralItem item) {
        if (item == null || item.isEmpty()) {
            throw new IllegalArgumentException("Cannot resolve max stack size for empty item");
        }
        Identifier id = Identifier.tryParse(item.getItemId());
        if (id != null) {
            var itemHolder = BuiltInRegistries.ITEM.get(id);
            if (itemHolder.isPresent() && itemHolder.get().value() != Items.AIR) {
                return itemHolder.get().value().getDefaultMaxStackSize();
            }
        }
        throw new IllegalArgumentException("Cannot resolve max stack size for " + item.getItemId());
    }

    private byte[] writeCanonical(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(10);
        out.writeUTF("");
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
                out.writeUTF(key);
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
        if (tag instanceof ListTag list) {
            writeListSorted(list, out);
            return;
        }
        tag.write(out);
    }

    private void writeListSorted(ListTag list, DataOutputStream out) throws IOException {
        byte elementType = identifyListElementType(list);
        out.writeByte(elementType);
        out.writeInt(list.size());
        for (Tag element : list) {
            writeTag(wrapListElementIfNeeded(elementType, element), out);
        }
    }

    private byte identifyListElementType(ListTag list) {
        byte homogenousType = 0;
        for (Tag element : list) {
            byte elementType = element.getId();
            if (homogenousType == 0) {
                homogenousType = elementType;
            } else if (homogenousType != elementType) {
                return 10;
            }
        }
        return homogenousType;
    }

    private Tag wrapListElementIfNeeded(byte elementType, Tag element) {
        if (elementType != 10) return element;
        if (element instanceof CompoundTag compound && !(compound.size() == 1 && compound.contains(""))) {
            return compound;
        }
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("", element);
        return wrapper;
    }

    private void debugCanDeserializeFailure(NeutralItem item, String stage, Exception error) {
        byte[] extra = item.getExtraData();
        String message = "[Exchange|Debug][Compat][canDeserialize] fail stage=" + stage
                + " id=" + item.getItemId()
                + " count=" + item.getCount()
                + " incompatible=" + item.isIncompatible()
                + " extraLen=" + (extra == null ? -1 : extra.length)
                + " extraHash=" + Arrays.hashCode(extra)
                + (error == null ? "" : " error=" + error.getClass().getSimpleName() + ": " + error.getMessage());
        System.out.println(message);
    }
}
