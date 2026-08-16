package org.edtp.theexchange.network.codec;

import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.*;
import org.edtp.theexchange.util.BinaryIO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class MessageCodec {
    private static final int MAX_SLOTS = 256;
    private MessageCodec() {}

    public static byte[] encodeMessage(Object msg) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            if (msg instanceof AuthRequest m) encodeAuthRequest(out, m);
            else if (msg instanceof AuthResponse m) encodeAuthResponse(out, m);
            else if (msg instanceof Heartbeat m) encodeHeartbeat(out, m);
            else if (msg instanceof PlayerInventoryAccessRequest m) encodePlayerInventoryAccessRequest(out, m);
            else if (msg instanceof PlayerInventoryAccessResponse m) encodePlayerInventoryAccessResponse(out, m);
            else if (msg instanceof QueryTimestampRequest m) encodeQueryTimestampRequest(out, m);
            else if (msg instanceof QueryTimestampResponse m) encodeQueryTimestampResponse(out, m);
            else if (msg instanceof QueryItemsRequest m) encodeQueryItemsRequest(out, m);
            else if (msg instanceof QueryItemsResponse m) encodeQueryItemsResponse(out, m);
            else if (msg instanceof QuerySlotVersionRequest m) encodeQuerySlotVersionRequest(out, m);
            else if (msg instanceof QuerySlotVersionResponse m) encodeQuerySlotVersionResponse(out, m);
            else if (msg instanceof QuerySlotStateRequest m) encodeQuerySlotStateRequest(out, m);
            else if (msg instanceof SlotStateResponse m) encodeSlotStateResponse(out, m);
            else if (msg instanceof QuerySlotVersionsRequest m) encodeQuerySlotVersionsRequest(out, m);
            else if (msg instanceof SlotVersionsResponse m) encodeSlotVersionsResponse(out, m);
            else if (msg instanceof QuerySlotsRequest m) encodeQuerySlotsRequest(out, m);
            else if (msg instanceof SlotsStateResponse m) encodeSlotsStateResponse(out, m);
            else if (msg instanceof MutationExecute m) encodeMutationExecute(out, m);
            else if (msg instanceof MutationResultMessage m) encodeMutationResult(out, m);
            else if (msg instanceof TransactionQuery m) encodeTransactionQuery(out, m);
            else if (msg instanceof TransactionStatus m) encodeTransactionStatus(out, m);
            else if (msg instanceof TransactionSettled m) encodeTransactionSettled(out, m);
            else if (msg instanceof TransactionClosed m) encodeTransactionClosed(out, m);
            else if (msg instanceof PushUpdate m) encodePushUpdate(out, m);
            else if (msg instanceof ErrorMessage m) encodeError(out, m);
            else throw new IllegalArgumentException("Unknown message type: " + msg.getClass());
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode " + msg.getClass().getSimpleName(), e);
        }
    }

    public static Object decodeMessage(FrameType type, byte[] payload) {
        if (payload == null) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            return switch (type) {
                case AUTH_REQUEST -> decodeAuthRequest(in);
                case AUTH_RESPONSE -> decodeAuthResponse(in);
                case HEARTBEAT -> decodeHeartbeat(in);
                case PLAYER_INVENTORY_ACCESS -> decodePlayerInventoryAccessRequest(in);
                case PLAYER_INVENTORY_ACCESS_RESPONSE -> decodePlayerInventoryAccessResponse(in);
                case QUERY_TIMESTAMP -> decodeQueryTimestampRequest(in);
                case TIMESTAMP_RESPONSE -> decodeQueryTimestampResponse(in);
                case QUERY_ITEMS -> decodeQueryItemsRequest(in);
                case ITEMS_RESPONSE -> decodeQueryItemsResponse(in);
                case QUERY_SLOT_VERSION -> decodeQuerySlotVersionRequest(in);
                case SLOT_VERSION_RESPONSE -> decodeQuerySlotVersionResponse(in);
                case QUERY_SLOT_STATE -> decodeQuerySlotStateRequest(in);
                case SLOT_STATE_RESPONSE -> decodeSlotStateResponse(in);
                case QUERY_SLOT_VERSIONS -> decodeQuerySlotVersionsRequest(in);
                case SLOT_VERSIONS_RESPONSE -> decodeSlotVersionsResponse(in);
                case QUERY_SLOTS -> decodeQuerySlotsRequest(in);
                case SLOTS_STATE_RESPONSE -> decodeSlotsStateResponse(in);
                case MUTATION_EXECUTE, MUTATION_RECOVER -> decodeMutationExecute(in);
                case MUTATION_RESULT -> decodeMutationResult(in);
                case TRANSACTION_QUERY -> decodeTransactionQuery(in);
                case TRANSACTION_STATUS -> decodeTransactionStatus(in);
                case TRANSACTION_SETTLED -> decodeTransactionSettled(in);
                case TRANSACTION_CLOSED -> decodeTransactionClosed(in);
                case PUSH_UPDATE -> decodePushUpdate(in);
                case ERROR -> decodeError(in);
            };
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode " + type, e);
        }
    }

    private static void encodeAuthRequest(DataOutputStream out, AuthRequest m) throws IOException {
        BinaryIO.writeString(out, m.getServerName());
        BinaryIO.writeString(out, m.getPassword());
        BinaryIO.writeString(out, m.getVersion());
        BinaryIO.writeString(out, m.getMcVersion());
    }

    private static void encodeAuthResponse(DataOutputStream out, AuthResponse m) throws IOException {
        out.writeBoolean(m.isSuccess());
        BinaryIO.writeString(out, m.getMessage());
        BinaryIO.writeString(out, m.getServerName());
        BinaryIO.writeString(out, m.getMcVersion());
        out.writeLong(m.getLastModifiedTimestamp());
        BinaryIO.writeString(out, m.getVersion());
    }

    private static void encodeHeartbeat(DataOutputStream out, Heartbeat m) throws IOException {
        out.writeBoolean(m.isReply());
        out.writeLong(m.getTimestamp());
    }

    private static void encodePlayerInventoryAccessRequest(DataOutputStream out,
                                                           PlayerInventoryAccessRequest m) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        BinaryIO.writeString(out, m.getOwnerName());
        BinaryIO.writeString(out, m.getPassword());
        BinaryIO.writeString(out, m.getRequesterUuid());
        BinaryIO.writeString(out, m.getRequesterName());
    }

    private static void encodePlayerInventoryAccessResponse(DataOutputStream out,
                                                            PlayerInventoryAccessResponse m) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        out.writeBoolean(m.isSuccess());
        BinaryIO.writeString(out, m.getFailReason());
        BinaryIO.writeString(out, m.getOwnerName());
        BinaryIO.writeString(out, m.getToken());
        writeInventoryScope(out, m.getScope());
        out.writeLong(m.getExpiresAt());
        out.writeLong(m.getSessionTtlMillis());
        out.writeLong(m.getLockedUntil());
    }

    private static void encodeQueryTimestampRequest(DataOutputStream out, QueryTimestampRequest m) throws IOException {
        out.writeLong(m.getCachedTimestamp());
    }

    private static void encodeQueryTimestampResponse(DataOutputStream out, QueryTimestampResponse m) throws IOException {
        out.writeLong(m.getCurrentTimestamp());
        out.writeBoolean(m.isChanged());
    }

    private static void encodeQueryItemsRequest(DataOutputStream out, QueryItemsRequest m) throws IOException {
        out.writeInt(m.getOffset());
        out.writeInt(m.getLimit());
    }

    private static void encodeQueryItemsResponse(DataOutputStream out, QueryItemsResponse m) throws IOException {
        List<NeutralItem> items = m.getItems();
        out.writeInt(m.getTotalSlots());
        out.writeLong(m.getTimestamp());
        BinaryIO.writeString(out, m.getServerVersion());
        out.writeInt(items != null ? items.size() : 0);
        if (items != null) {
            for (NeutralItem item : items) {
                BinaryIO.writeNullableNeutralItem(out, item);
            }
        }
    }

    private static void encodeQuerySlotVersionRequest(DataOutputStream out, QuerySlotVersionRequest m) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        out.writeInt(m.getSlot());
        writeInventoryAccess(out, m.getAccess());
    }

    private static void encodeQuerySlotVersionResponse(DataOutputStream out, QuerySlotVersionResponse m) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        out.writeInt(m.getSlot());
        out.writeInt(m.getVersion());
        writeResponseExtensions(out, m.getScope(), m.isSuccess(), m.getFailReason());
    }

    private static void encodeQuerySlotStateRequest(DataOutputStream out, QuerySlotStateRequest m) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        out.writeInt(m.getSlot());
        writeInventoryAccess(out, m.getAccess());
    }

    private static void encodeSlotStateResponse(DataOutputStream out, SlotStateResponse m) throws IOException {
        encodeSlotStateResponse(out, m, true);
    }

    private static void encodeSlotStateResponse(DataOutputStream out, SlotStateResponse m,
                                                boolean includeResponseExtensions) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        out.writeInt(m.getSlot());
        BinaryIO.writeNullableNeutralItem(out, m.getItem());
        out.writeInt(m.getVersion());
        if (includeResponseExtensions) {
            writeResponseExtensions(out, m.getScope(), m.isSuccess(), m.getFailReason());
        }
    }

    private static void encodeQuerySlotVersionsRequest(DataOutputStream out, QuerySlotVersionsRequest m) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        writeInventoryAccess(out, m.getAccess());
    }

    private static void encodeSlotVersionsResponse(DataOutputStream out, SlotVersionsResponse m) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        List<Integer> versions = m.getVersions();
        out.writeInt(versions != null ? versions.size() : 0);
        if (versions != null) {
            for (Integer version : versions) {
                out.writeInt(version != null ? version : 0);
            }
        }
        writeResponseExtensions(out, m.getScope(), m.isSuccess(), m.getFailReason());
    }

    private static void encodeQuerySlotsRequest(DataOutputStream out, QuerySlotsRequest m) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        List<Integer> slots = m.getSlots();
        out.writeInt(slots != null ? slots.size() : 0);
        if (slots != null) {
            for (int slot : slots) {
                out.writeInt(slot);
            }
        }
        writeInventoryAccess(out, m.getAccess());
    }

    private static void encodeSlotsStateResponse(DataOutputStream out, SlotsStateResponse m) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        List<SlotStateResponse> slots = m.getSlots();
        out.writeInt(slots != null ? slots.size() : 0);
        if (slots != null) {
            for (SlotStateResponse slot : slots) {
                encodeSlotStateResponse(out, slot, false);
            }
        }
        writeResponseExtensions(out, m.getScope(), m.isSuccess(), m.getFailReason());
    }

    private static void encodeMutationExecute(DataOutputStream out, MutationExecute m) throws IOException {
        BinaryIO.writeString(out, m.getTransactionId());
        BinaryIO.writeString(out, m.getIntentHash());
        BinaryIO.writeString(out, m.getKind() != null ? m.getKind().name() : null);
        out.writeInt(m.getSlot());
        BinaryIO.writeNullableNeutralItem(out, m.getOfferedItem());
        BinaryIO.writeString(out, m.getExpectedItemId());
        out.writeInt(m.getExpectedVersion());
        out.writeInt(m.getCount());
        out.writeBoolean(m.isBoundedMerge());
        BinaryIO.writeString(out, m.getPlayerUuid());
        BinaryIO.writeString(out, m.getPlayerName());
        writeInventoryAccess(out, m.getAccess());
    }

    private static void encodeMutationResult(DataOutputStream out, MutationResultMessage m) throws IOException {
        BinaryIO.writeString(out, m.getTransactionId());
        BinaryIO.writeString(out, m.getIntentHash());
        BinaryIO.writeString(out, m.getResultHash());
        BinaryIO.writeString(out, m.getKind() != null ? m.getKind().name() : null);
        out.writeBoolean(m.isSuccess());
        out.writeInt(m.getSlot());
        BinaryIO.writeNullableNeutralItem(out, m.getCurrentItem());
        BinaryIO.writeNullableNeutralItem(out, m.getTransferredItem());
        BinaryIO.writeString(out, m.getFailReason());
        out.writeLong(m.getNewTimestamp());
        out.writeInt(m.getNewVersion());
        writeInventoryScope(out, m.getScope());
    }

    private static void encodeTransactionQuery(DataOutputStream out, TransactionQuery m) throws IOException {
        BinaryIO.writeString(out, m.transactionId());
        BinaryIO.writeString(out, m.intentHash());
    }

    private static void encodeTransactionStatus(DataOutputStream out, TransactionStatus m) throws IOException {
        BinaryIO.writeString(out, m.getTransactionId());
        BinaryIO.writeString(out, m.getIntentHash());
        BinaryIO.writeString(out, m.getState() != null ? m.getState().name() : null);
        out.writeBoolean(m.getResult() != null);
        if (m.getResult() != null) {
            encodeMutationResult(out, m.getResult());
        }
    }

    private static void encodeTransactionSettled(DataOutputStream out, TransactionSettled m) throws IOException {
        BinaryIO.writeString(out, m.transactionId());
        BinaryIO.writeString(out, m.resultHash());
    }

    private static void encodeTransactionClosed(DataOutputStream out, TransactionClosed m) throws IOException {
        BinaryIO.writeString(out, m.transactionId());
        BinaryIO.writeString(out, m.resultHash());
    }

    private static void encodePushUpdate(DataOutputStream out, PushUpdate m) throws IOException {
        List<Integer> slots = m.getChangedSlots();
        out.writeLong(m.getTimestamp());
        out.writeInt(slots != null ? slots.size() : 0);
        if (slots != null) {
            for (int slot : slots) {
                out.writeInt(slot);
            }
        }
        writeInventoryScope(out, m.getScope());
    }

    private static void encodeError(DataOutputStream out, ErrorMessage m) throws IOException {
        out.writeInt(m.getCode());
        BinaryIO.writeString(out, m.getMessage());
    }

    private static AuthRequest decodeAuthRequest(DataInputStream in) throws IOException {
        return new AuthRequest(BinaryIO.readString(in), BinaryIO.readString(in),
                BinaryIO.readString(in), BinaryIO.readString(in));
    }

    private static AuthResponse decodeAuthResponse(DataInputStream in) throws IOException {
        AuthResponse response = new AuthResponse(in.readBoolean(), BinaryIO.readString(in),
                BinaryIO.readString(in), BinaryIO.readString(in), in.readLong());
        response.setVersion(BinaryIO.readString(in));
        return response;
    }

    private static Heartbeat decodeHeartbeat(DataInputStream in) throws IOException {
        return new Heartbeat(in.readBoolean(), in.readLong());
    }

    private static PlayerInventoryAccessRequest decodePlayerInventoryAccessRequest(DataInputStream in)
            throws IOException {
        return new PlayerInventoryAccessRequest(BinaryIO.readString(in), BinaryIO.readString(in),
                BinaryIO.readString(in), BinaryIO.readString(in), BinaryIO.readString(in));
    }

    private static PlayerInventoryAccessResponse decodePlayerInventoryAccessResponse(DataInputStream in)
            throws IOException {
        PlayerInventoryAccessResponse response = new PlayerInventoryAccessResponse();
        response.setRequestId(BinaryIO.readString(in));
        response.setSuccess(in.readBoolean());
        response.setFailReason(BinaryIO.readString(in));
        response.setOwnerName(BinaryIO.readString(in));
        response.setToken(BinaryIO.readString(in));
        response.setScope(readInventoryScope(in));
        response.setExpiresAt(in.readLong());
        response.setSessionTtlMillis(in.readLong());
        response.setLockedUntil(in.readLong());
        return response;
    }

    private static QueryTimestampRequest decodeQueryTimestampRequest(DataInputStream in) throws IOException {
        return new QueryTimestampRequest(in.readLong());
    }

    private static QueryTimestampResponse decodeQueryTimestampResponse(DataInputStream in) throws IOException {
        return new QueryTimestampResponse(in.readLong(), in.readBoolean());
    }

    private static QueryItemsRequest decodeQueryItemsRequest(DataInputStream in) throws IOException {
        return new QueryItemsRequest(in.readInt(), in.readInt());
    }

    private static QueryItemsResponse decodeQueryItemsResponse(DataInputStream in) throws IOException {
        int totalSlots = in.readInt();
        long timestamp = in.readLong();
        String serverVersion = BinaryIO.readString(in);
        int size = readListSize(in, "items");
        List<NeutralItem> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            items.add(BinaryIO.readNullableNeutralItem(in));
        }
        return new QueryItemsResponse(items, totalSlots, timestamp, serverVersion);
    }

    private static QuerySlotVersionRequest decodeQuerySlotVersionRequest(DataInputStream in) throws IOException {
        QuerySlotVersionRequest request = new QuerySlotVersionRequest(BinaryIO.readString(in), in.readInt());
        request.setAccess(readInventoryAccess(in));
        return request;
    }

    private static QuerySlotVersionResponse decodeQuerySlotVersionResponse(DataInputStream in) throws IOException {
        QuerySlotVersionResponse response = new QuerySlotVersionResponse(BinaryIO.readString(in), in.readInt(), in.readInt());
        response.setScope(readInventoryScope(in));
        readQueryResult(in, response::setSuccess, response::setFailReason);
        return response;
    }

    private static QuerySlotStateRequest decodeQuerySlotStateRequest(DataInputStream in) throws IOException {
        QuerySlotStateRequest request = new QuerySlotStateRequest(BinaryIO.readString(in), in.readInt());
        request.setAccess(readInventoryAccess(in));
        return request;
    }

    private static SlotStateResponse decodeSlotStateResponse(DataInputStream in) throws IOException {
        SlotStateResponse response = new SlotStateResponse(BinaryIO.readString(in), in.readInt(), BinaryIO.readNullableNeutralItem(in), in.readInt());
        response.setScope(readInventoryScope(in));
        readQueryResult(in, response::setSuccess, response::setFailReason);
        return response;
    }

    private static SlotStateResponse decodeSlotStateResponseWithoutScope(DataInputStream in) throws IOException {
        return new SlotStateResponse(BinaryIO.readString(in), in.readInt(), BinaryIO.readNullableNeutralItem(in), in.readInt());
    }

    private static QuerySlotVersionsRequest decodeQuerySlotVersionsRequest(DataInputStream in) throws IOException {
        QuerySlotVersionsRequest request = new QuerySlotVersionsRequest(BinaryIO.readString(in));
        request.setAccess(readInventoryAccess(in));
        return request;
    }

    private static SlotVersionsResponse decodeSlotVersionsResponse(DataInputStream in) throws IOException {
        String requestId = BinaryIO.readString(in);
        int size = readListSize(in, "slot versions");
        List<Integer> versions = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            versions.add(in.readInt());
        }
        SlotVersionsResponse response = new SlotVersionsResponse(requestId, versions);
        response.setScope(readInventoryScope(in));
        readQueryResult(in, response::setSuccess, response::setFailReason);
        return response;
    }

    private static QuerySlotsRequest decodeQuerySlotsRequest(DataInputStream in) throws IOException {
        String requestId = BinaryIO.readString(in);
        int size = readListSize(in, "query slots");
        List<Integer> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(in.readInt());
        }
        QuerySlotsRequest request = new QuerySlotsRequest(requestId, slots);
        request.setAccess(readInventoryAccess(in));
        return request;
    }

    private static SlotsStateResponse decodeSlotsStateResponse(DataInputStream in) throws IOException {
        String requestId = BinaryIO.readString(in);
        int size = readListSize(in, "slot states");
        List<SlotStateResponse> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(decodeSlotStateResponseWithoutScope(in));
        }
        SlotsStateResponse response = new SlotsStateResponse(requestId, slots);
        response.setScope(readInventoryScope(in));
        readQueryResult(in, response::setSuccess, response::setFailReason);
        return response;
    }

    private static MutationExecute decodeMutationExecute(DataInputStream in) throws IOException {
        MutationExecute request = new MutationExecute();
        request.setTransactionId(BinaryIO.readString(in));
        request.setIntentHash(BinaryIO.readString(in));
        request.setKind(readEnum(in, MutationKind.class, "mutation kind"));
        request.setSlot(in.readInt());
        request.setOfferedItem(BinaryIO.readNullableNeutralItem(in));
        request.setExpectedItemId(BinaryIO.readString(in));
        request.setExpectedVersion(in.readInt());
        request.setCount(in.readInt());
        request.setBoundedMerge(in.readBoolean());
        request.setPlayerUuid(BinaryIO.readString(in));
        request.setPlayerName(BinaryIO.readString(in));
        request.setAccess(readInventoryAccess(in));
        return request;
    }

    private static MutationResultMessage decodeMutationResult(DataInputStream in) throws IOException {
        MutationResultMessage result = new MutationResultMessage();
        result.setTransactionId(BinaryIO.readString(in));
        result.setIntentHash(BinaryIO.readString(in));
        result.setResultHash(BinaryIO.readString(in));
        result.setKind(readEnum(in, MutationKind.class, "mutation kind"));
        result.setSuccess(in.readBoolean());
        result.setSlot(in.readInt());
        result.setCurrentItem(BinaryIO.readNullableNeutralItem(in));
        result.setTransferredItem(BinaryIO.readNullableNeutralItem(in));
        result.setFailReason(BinaryIO.readString(in));
        result.setNewTimestamp(in.readLong());
        result.setNewVersion(in.readInt());
        result.setScope(readInventoryScope(in));
        return result;
    }

    private static TransactionQuery decodeTransactionQuery(DataInputStream in) throws IOException {
        return new TransactionQuery(BinaryIO.readString(in), BinaryIO.readString(in));
    }

    private static TransactionStatus decodeTransactionStatus(DataInputStream in) throws IOException {
        TransactionStatus status = new TransactionStatus();
        status.setTransactionId(BinaryIO.readString(in));
        status.setIntentHash(BinaryIO.readString(in));
        status.setState(readEnum(in, TransactionStatus.State.class, "transaction state"));
        if (in.readBoolean()) {
            status.setResult(decodeMutationResult(in));
        }
        return status;
    }

    private static TransactionSettled decodeTransactionSettled(DataInputStream in) throws IOException {
        return new TransactionSettled(BinaryIO.readString(in), BinaryIO.readString(in));
    }

    private static TransactionClosed decodeTransactionClosed(DataInputStream in) throws IOException {
        return new TransactionClosed(BinaryIO.readString(in), BinaryIO.readString(in));
    }

    private static PushUpdate decodePushUpdate(DataInputStream in) throws IOException {
        PushUpdate update = new PushUpdate();
        update.setTimestamp(in.readLong());
        int size = readListSize(in, "push update slots");
        List<Integer> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(in.readInt());
        }
        update.setChangedSlots(slots);
        update.setScope(readInventoryScope(in));
        return update;
    }

    private static ErrorMessage decodeError(DataInputStream in) throws IOException {
        return new ErrorMessage(in.readInt(), BinaryIO.readString(in));
    }

    private static int readListSize(DataInputStream in, String label) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_SLOTS) {
            throw new IOException(label + " list too large: " + size);
        }
        return size;
    }

    private static void writeInventoryAccess(DataOutputStream out, InventoryAccess access) throws IOException {
        InventoryAccess value = access != null ? access : InventoryAccess.server();
        BinaryIO.writeString(out, value.type().name());
        BinaryIO.writeString(out, value.ownerName());
        BinaryIO.writeString(out, value.token());
        BinaryIO.writeString(out, value.requesterUuid());
        BinaryIO.writeString(out, value.requesterName());
        out.writeBoolean(value.resolvedScope() != null);
        if (value.resolvedScope() != null) {
            writeInventoryScope(out, value.resolvedScope());
        }
        out.writeLong(value.expiresAt());
        out.writeLong(value.sessionTtlMillis());
    }

    private static InventoryAccess readInventoryAccess(DataInputStream in) throws IOException {
        String type = BinaryIO.readString(in);
        String ownerName = BinaryIO.readString(in);
        String token = BinaryIO.readString(in);
        String requesterUuid = BinaryIO.readString(in);
        String requesterName = BinaryIO.readString(in);
        InventoryScope resolvedScope = in.readBoolean() ? readInventoryScope(in) : null;
        long expiresAt = in.readLong();
        long sessionTtlMillis = in.readLong();
        InventoryScope.ScopeType scopeType = InventoryScope.ScopeType.SERVER;
        if (type != null && !type.isBlank()) {
            scopeType = InventoryScope.ScopeType.valueOf(type.trim().toUpperCase(java.util.Locale.ROOT));
        }
        return scopeType == InventoryScope.ScopeType.PLAYER
                ? InventoryAccess.playerSession(ownerName, token, requesterUuid, requesterName,
                        resolvedScope, expiresAt, sessionTtlMillis)
                : InventoryAccess.server();
    }

    private static void writeInventoryScope(DataOutputStream out, InventoryScope scope) throws IOException {
        InventoryScope value = scope != null ? scope : InventoryScope.server();
        BinaryIO.writeString(out, value.typeName());
        BinaryIO.writeString(out, value.getScopeId());
    }

    private static void writeResponseExtensions(DataOutputStream out, InventoryScope scope,
                                                boolean success, String failReason) throws IOException {
        writeInventoryScope(out, scope);
        out.writeBoolean(success);
        BinaryIO.writeString(out, failReason);
    }

    private static InventoryScope readInventoryScope(DataInputStream in) throws IOException {
        return InventoryScope.of(BinaryIO.readString(in), BinaryIO.readString(in));
    }

    private static <E extends Enum<E>> E readEnum(DataInputStream in, Class<E> type,
                                                   String label) throws IOException {
        String value = BinaryIO.readString(in);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing " + label);
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid " + label + ": " + value, error);
        }
    }

    private interface BooleanSetter {
        void set(boolean value);
    }

    private interface StringSetter {
        void set(String value);
    }

    private static void readQueryResult(DataInputStream in, BooleanSetter successSetter,
                                        StringSetter failReasonSetter) throws IOException {
        successSetter.set(in.readBoolean());
        failReasonSetter.set(BinaryIO.readString(in));
    }
}
