package org.edtp.theexchange.network.protocol;

import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.network.protocol.messages.MutationExecute;
import org.edtp.theexchange.network.protocol.messages.MutationResultMessage;
import org.edtp.theexchange.util.BinaryIO;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical hashes bind a transaction id to one immutable intent and one exact outcome. */
public final class MutationHashes {
    private MutationHashes() {}

    public static String intent(MutationExecute request) {
        return hash(out -> {
            BinaryIO.writeString(out, request.getTransactionId());
            BinaryIO.writeString(out, request.getKind() != null ? request.getKind().name() : null);
            out.writeInt(request.getSlot());
            writeItem(out, request.getOfferedItem());
            BinaryIO.writeString(out, request.getExpectedItemId());
            out.writeInt(request.getExpectedVersion());
            out.writeInt(request.getCount());
            out.writeBoolean(request.isBoundedMerge());
            BinaryIO.writeString(out, request.getPlayerUuid());
            writeAccessIdentity(out, request.getAccess());
        });
    }

    public static String result(MutationResultMessage result) {
        return hash(out -> {
            BinaryIO.writeString(out, result.getTransactionId());
            BinaryIO.writeString(out, result.getIntentHash());
            BinaryIO.writeString(out, result.getKind() != null ? result.getKind().name() : null);
            out.writeBoolean(result.isSuccess());
            out.writeInt(result.getSlot());
            writeItem(out, result.getCurrentItem());
            writeItem(out, result.getTransferredItem());
            BinaryIO.writeString(out, result.getFailReason());
            out.writeLong(result.getNewTimestamp());
            out.writeInt(result.getNewVersion());
            writeScope(out, result.getScope());
        });
    }

    public static boolean validIntent(MutationExecute request) {
        return request != null && request.getIntentHash() != null
                && MessageDigest.isEqual(bytes(request.getIntentHash()), bytes(intent(request)));
    }

    public static boolean validResult(MutationResultMessage result) {
        return result != null && result.getResultHash() != null
                && MessageDigest.isEqual(bytes(result.getResultHash()), bytes(result(result)));
    }

    private static void writeItem(DataOutputStream out, NeutralItem item) throws IOException {
        out.writeBoolean(item != null);
        if (item != null) {
            item.writeTo(out);
        }
    }

    private static void writeAccessIdentity(DataOutputStream out, InventoryAccess access) throws IOException {
        InventoryAccess value = access != null ? access : InventoryAccess.server();
        BinaryIO.writeString(out, value.type().name());
        BinaryIO.writeString(out, value.ownerName());
        BinaryIO.writeString(out, value.requesterUuid());
    }

    private static void writeScope(DataOutputStream out, InventoryScope scope) throws IOException {
        InventoryScope value = scope != null ? scope : InventoryScope.server();
        BinaryIO.writeString(out, value.typeName());
        BinaryIO.writeString(out, value.getScopeId());
    }

    private static String hash(Writer writer) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out);
            out.flush();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Unable to hash mutation", error);
        }
    }

    private static byte[] bytes(String value) {
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException error) {
            return new byte[0];
        }
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }
}
