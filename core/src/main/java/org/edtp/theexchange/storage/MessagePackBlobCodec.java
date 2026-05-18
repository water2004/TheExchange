package org.edtp.theexchange.storage;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight MessagePack blob codec for serializing objects to/from byte arrays.
 * Uses reflection-free manual encoding for model classes to avoid annotation dependencies.
 */
public final class MessagePackBlobCodec {

    private MessagePackBlobCodec() {}

    @SuppressWarnings("unchecked")
    public static <T> T decode(byte[] blob, Class<T> clazz) {
        if (blob == null) return null;
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(blob)) {
            return (T) decodeObject(unpacker, clazz);
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode " + clazz.getSimpleName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> decodeList(byte[] blob, Class<T> elementClass) {
        if (blob == null) return new ArrayList<>();
        List<T> result = new ArrayList<>();
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(blob)) {
            int size = unpacker.unpackArrayHeader();
            for (int i = 0; i < size; i++) {
                if (unpacker.tryUnpackNil()) {
                    result.add(null);
                } else {
                    result.add((T) decodeObject(unpacker, elementClass));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode list of " + elementClass.getSimpleName(), e);
        }
        return result;
    }

    public static byte[] encode(Object obj) {
        if (obj == null) return null;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             MessagePacker packer = MessagePack.newDefaultPacker(bos)) {
            encodeObject(packer, obj);
            packer.close();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode " + obj.getClass().getSimpleName(), e);
        }
    }

    public static <T> byte[] encodeList(List<T> items) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             MessagePacker packer = MessagePack.newDefaultPacker(bos)) {
            packer.packArrayHeader(items.size());
            for (T item : items) {
                if (item == null) {
                    packer.packNil();
                } else {
                    encodeObject(packer, item);
                }
            }
            packer.close();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode list", e);
        }
    }

    private static Object decodeObject(MessageUnpacker unpacker, Class<?> clazz) throws IOException {
        if (clazz == org.edtp.theexchange.model.NeutralItem.class) {
            return decodeNeutralItem(unpacker);
        }
        throw new IllegalArgumentException("Unknown class for decoding: " + clazz.getName());
    }

    private static void encodeObject(MessagePacker packer, Object obj) throws IOException {
        if (obj instanceof org.edtp.theexchange.model.NeutralItem item) {
            encodeNeutralItem(packer, item);
        } else {
            throw new IllegalArgumentException("Unknown class for encoding: " + obj.getClass().getName());
        }
    }

    private static org.edtp.theexchange.model.NeutralItem decodeNeutralItem(MessageUnpacker unpacker) throws IOException {
        org.edtp.theexchange.model.NeutralItem item = new org.edtp.theexchange.model.NeutralItem();
        int size = unpacker.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            String key = unpacker.unpackString();
            switch (key) {
                case "id" -> item.setItemId(unpacker.unpackString());
                case "ct" -> item.setCount(unpacker.unpackInt());
                case "dn" -> item.setDisplayName(unpacker.unpackString());
                case "ed" -> {
                    int len = unpacker.unpackBinaryHeader();
                    item.setExtraData(unpacker.readPayload(len));
                }
                case "ic" -> item.setIncompatible(unpacker.unpackBoolean());
                case "sv" -> item.setSourceVersion(unpacker.unpackString());
                default -> unpacker.skipValue();
            }
        }
        return item;
    }

    private static void encodeNeutralItem(MessagePacker packer, org.edtp.theexchange.model.NeutralItem item) throws IOException {
        // Compact field names to minimize wire/db size
        packer.packMapHeader(6);
        packer.packString("id"); packer.packString(item.getItemId() != null ? item.getItemId() : "");
        packer.packString("ct"); packer.packInt(item.getCount());
        packer.packString("dn"); packer.packString(item.getDisplayName() != null ? item.getDisplayName() : "");
        if (item.getExtraData() != null) {
            packer.packString("ed");
            packer.packBinaryHeader(item.getExtraData().length);
            packer.writePayload(item.getExtraData());
        } else {
            packer.packString("ed");
            packer.packBinaryHeader(0);
            packer.writePayload(new byte[0]);
        }
        packer.packString("ic"); packer.packBoolean(item.isIncompatible());
        packer.packString("sv"); packer.packString(item.getSourceVersion() != null ? item.getSourceVersion() : "");
    }
}
