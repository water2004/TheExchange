package org.edtp.theexchange.model;

import java.util.Arrays;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

/**
 * Protocol-neutral item representation for cross-server and cross-version exchange.
 * The authoritative server MUST preserve extraData byte-for-byte (F-40).
 */
public class NeutralItem {

    private static final byte[] EMPTY_BYTES = new byte[0];

    private String itemId;
    private int count;
    private String displayName;
    private byte[] extraData;
    private boolean incompatible;
    private String sourceVersion;
    private int version = 1;

    public NeutralItem() {}

    public NeutralItem(String itemId, int count, String displayName, byte[] extraData,
                       boolean incompatible, String sourceVersion) {
        this.itemId = itemId;
        this.count = count;
        this.displayName = displayName;
        this.extraData = copyBytes(extraData);
        this.incompatible = incompatible;
        this.sourceVersion = sourceVersion;
    }

    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public byte[] getExtraData() { return copyBytes(extraData); }
    public void setExtraData(byte[] extraData) { this.extraData = copyBytes(extraData); }

    public boolean isIncompatible() { return incompatible; }
    public void setIncompatible(boolean incompatible) { this.incompatible = incompatible; }

    public String getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(String sourceVersion) { this.sourceVersion = sourceVersion; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public boolean isEmpty() {
        return itemId == null || count <= 0;
    }

    public NeutralItem copy() {
        byte[] extraCopy = extraData != null ? Arrays.copyOf(extraData, extraData.length) : null;
        NeutralItem copy = new NeutralItem(itemId, count, displayName, extraCopy, incompatible, sourceVersion);
        copy.setVersion(version);
        return copy;
    }

    public boolean sameStackKind(NeutralItem other) {
        if (other == null) return false;
        return Objects.equals(itemId, other.itemId)
                && Arrays.equals(normalizeExtra(extraData), normalizeExtra(other.extraData));
    }

    public static boolean sameStackKind(NeutralItem a, NeutralItem b) {
        return a != null && a.sameStackKind(b);
    }

    private static byte[] normalizeExtra(byte[] data) {
        return data == null || data.length == 0 ? EMPTY_BYTES : data;
    }

    private static byte[] copyBytes(byte[] data) {
        return data != null ? Arrays.copyOf(data, data.length) : null;
    }

    public void writeTo(DataOutput out) throws IOException {
        org.edtp.theexchange.util.BinaryIO.writeString(out, itemId);
        out.writeInt(count);
        org.edtp.theexchange.util.BinaryIO.writeString(out, displayName);
        org.edtp.theexchange.util.BinaryIO.writeBytes(out, extraData);
        out.writeBoolean(incompatible);
        org.edtp.theexchange.util.BinaryIO.writeString(out, sourceVersion);
        out.writeInt(version);
    }

    public static NeutralItem readFrom(DataInput in) throws IOException {
        NeutralItem item = new NeutralItem();
        item.setItemId(org.edtp.theexchange.util.BinaryIO.readString(in));
        item.setCount(in.readInt());
        item.setDisplayName(org.edtp.theexchange.util.BinaryIO.readString(in));
        item.setExtraData(org.edtp.theexchange.util.BinaryIO.readBytes(in));
        item.setIncompatible(in.readBoolean());
        item.setSourceVersion(org.edtp.theexchange.util.BinaryIO.readString(in));
        item.setVersion(in.readInt());
        return item;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NeutralItem that)) return false;
        return count == that.count
                && incompatible == that.incompatible
                && Objects.equals(itemId, that.itemId)
                && Arrays.equals(extraData, that.extraData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, count, incompatible, Arrays.hashCode(extraData));
    }
}
