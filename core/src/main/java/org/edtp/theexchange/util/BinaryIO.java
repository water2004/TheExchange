package org.edtp.theexchange.util;

import org.edtp.theexchange.model.NeutralItem;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class BinaryIO {

    public static final int MAX_STRING_BYTES = 64 * 1024;
    public static final int MAX_BLOB_BYTES = 16 * 1024 * 1024;

    private BinaryIO() {}

    public static void writeString(DataOutput out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeLength(out, bytes.length, MAX_STRING_BYTES);
        out.write(bytes);
    }

    public static String readString(DataInput in) throws IOException {
        int length = in.readInt();
        if (length < 0) return null;
        if (length > MAX_STRING_BYTES) {
            throw new IOException("String too large: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeBytes(DataOutput out, byte[] data) throws IOException {
        if (data == null) {
            out.writeInt(-1);
            return;
        }
        writeLength(out, data.length, MAX_BLOB_BYTES);
        out.write(data);
    }

    public static byte[] readBytes(DataInput in) throws IOException {
        int length = in.readInt();
        if (length < 0) return null;
        if (length > MAX_BLOB_BYTES) {
            throw new IOException("Binary blob too large: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    public static void writeNullableNeutralItem(DataOutput out, NeutralItem item) throws IOException {
        out.writeBoolean(item != null);
        if (item != null) {
            item.writeTo(out);
        }
    }

    public static NeutralItem readNullableNeutralItem(DataInput in) throws IOException {
        return in.readBoolean() ? NeutralItem.readFrom(in) : null;
    }

    private static void writeLength(DataOutput out, int length, int max) throws IOException {
        if (length < 0 || length > max) {
            throw new IOException("Length out of range: " + length);
        }
        out.writeInt(length);
    }
}
