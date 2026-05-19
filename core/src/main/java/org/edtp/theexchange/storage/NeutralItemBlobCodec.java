package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.NeutralItem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class NeutralItemBlobCodec {

    private NeutralItemBlobCodec() {}

    public static byte[] encode(NeutralItem item) {
        if (item == null) return null;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            item.writeTo(out);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode NeutralItem", e);
        }
    }

    public static NeutralItem decode(byte[] blob) {
        if (blob == null) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob))) {
            return NeutralItem.readFrom(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode NeutralItem", e);
        }
    }

    public static byte[] encodeList(List<NeutralItem> items) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            List<NeutralItem> source = items != null ? items : new ArrayList<>();
            out.writeInt(source.size());
            for (NeutralItem item : source) {
                out.writeBoolean(item != null);
                if (item != null) {
                    item.writeTo(out);
                }
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode NeutralItem list", e);
        }
    }

    public static List<NeutralItem> decodeList(byte[] blob) {
        List<NeutralItem> items = new ArrayList<>();
        if (blob == null) return items;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob))) {
            int size = in.readInt();
            for (int i = 0; i < size; i++) {
                if (in.readBoolean()) {
                    items.add(NeutralItem.readFrom(in));
                } else {
                    items.add(null);
                }
            }
            return items;
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode NeutralItem list", e);
        }
    }
}
