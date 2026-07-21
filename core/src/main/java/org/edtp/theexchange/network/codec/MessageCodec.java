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
    private static final int PROTOCOL_V2 = 2;

    private MessageCodec() {}

    public static byte[] encodeMessage(Object msg) {
        return encodeMessage(msg, PROTOCOL_V2);
    }

    public static byte[] encodeMessage(Object msg, int protocolVersion) {
        boolean includeV2Fields = protocolVersion >= PROTOCOL_V2;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            if (msg instanceof AuthRequest m) encodeAuthRequest(out, m);
            else if (msg instanceof AuthResponse m) encodeAuthResponse(out, m, includeV2Fields);
            else if (msg instanceof Heartbeat m) encodeHeartbeat(out, m);
            else if (msg instanceof PlayerInventoryAccessRequest m) {
                requireProtocolV2(includeV2Fields, "player inventory access request");
                encodePlayerInventoryAccessRequest(out, m);
            }
            else if (msg instanceof PlayerInventoryAccessResponse m) {
                requireProtocolV2(includeV2Fields, "player inventory access response");
                encodePlayerInventoryAccessResponse(out, m);
            }
            else if (msg instanceof QueryTimestampRequest m) encodeQueryTimestampRequest(out, m);
            else if (msg instanceof QueryTimestampResponse m) encodeQueryTimestampResponse(out, m);
            else if (msg instanceof QueryItemsRequest m) encodeQueryItemsRequest(out, m);
            else if (msg instanceof QueryItemsResponse m) encodeQueryItemsResponse(out, m);
            else if (msg instanceof QuerySlotVersionRequest m) encodeQuerySlotVersionRequest(out, m, includeV2Fields);
            else if (msg instanceof QuerySlotVersionResponse m) encodeQuerySlotVersionResponse(out, m, includeV2Fields);
            else if (msg instanceof QuerySlotStateRequest m) encodeQuerySlotStateRequest(out, m, includeV2Fields);
            else if (msg instanceof SlotStateResponse m) encodeSlotStateResponse(out, m, includeV2Fields);
            else if (msg instanceof QuerySlotVersionsRequest m) encodeQuerySlotVersionsRequest(out, m, includeV2Fields);
            else if (msg instanceof SlotVersionsResponse m) encodeSlotVersionsResponse(out, m, includeV2Fields);
            else if (msg instanceof QuerySlotsRequest m) encodeQuerySlotsRequest(out, m, includeV2Fields);
            else if (msg instanceof SlotsStateResponse m) encodeSlotsStateResponse(out, m, includeV2Fields);
            else if (msg instanceof PutItemRequest m) encodePutItemRequest(out, m, includeV2Fields);
            else if (msg instanceof PutItemResponse m) encodePutItemResponse(out, m, includeV2Fields);
            else if (msg instanceof TakeItemRequest m) encodeTakeItemRequest(out, m, includeV2Fields);
            else if (msg instanceof TakeItemResponse m) encodeTakeItemResponse(out, m, includeV2Fields);
            else if (msg instanceof SwapItemRequest m) encodeSwapItemRequest(out, m, includeV2Fields);
            else if (msg instanceof SwapItemResponse m) encodeSwapItemResponse(out, m, includeV2Fields);
            else if (msg instanceof PushUpdate m) encodePushUpdate(out, m, includeV2Fields);
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
                case PUT_ITEM -> decodePutItemRequest(in);
                case PUT_ITEM_RESPONSE -> decodePutItemResponse(in);
                case TAKE_ITEM -> decodeTakeItemRequest(in);
                case TAKE_ITEM_RESPONSE -> decodeTakeItemResponse(in);
                case SWAP_ITEM -> decodeSwapItemRequest(in);
                case SWAP_ITEM_RESPONSE -> decodeSwapItemResponse(in);
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

    private static void encodeAuthResponse(DataOutputStream out, AuthResponse m, boolean includeV2Fields) throws IOException {
        out.writeBoolean(m.isSuccess());
        BinaryIO.writeString(out, m.getMessage());
        BinaryIO.writeString(out, m.getServerName());
        BinaryIO.writeString(out, m.getMcVersion());
        out.writeLong(m.getLastModifiedTimestamp());
        if (includeV2Fields) {
            BinaryIO.writeString(out, m.getVersion());
        }
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

    private static void encodeQuerySlotVersionRequest(DataOutputStream out, QuerySlotVersionRequest m,
                                                      boolean includeV2Fields) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        out.writeInt(m.getSlot());
        writeInventoryAccessIfSupported(out, m.getAccess(), includeV2Fields);
    }

    private static void encodeQuerySlotVersionResponse(DataOutputStream out, QuerySlotVersionResponse m,
                                                       boolean includeV2Fields) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        out.writeInt(m.getSlot());
        out.writeInt(m.getVersion());
        writeResponseExtensionsIfSupported(out, m.getScope(), m.isSuccess(), m.getFailReason(), includeV2Fields);
    }

    private static void encodeQuerySlotStateRequest(DataOutputStream out, QuerySlotStateRequest m,
                                                    boolean includeV2Fields) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        out.writeInt(m.getSlot());
        writeInventoryAccessIfSupported(out, m.getAccess(), includeV2Fields);
    }

    private static void encodeSlotStateResponse(DataOutputStream out, SlotStateResponse m,
                                                boolean includeV2Fields) throws IOException {
        encodeSlotStateResponse(out, m, includeV2Fields, true);
    }

    private static void encodeSlotStateResponse(DataOutputStream out, SlotStateResponse m,
                                                boolean includeV2Fields, boolean includeResponseExtensions) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        out.writeInt(m.getSlot());
        BinaryIO.writeNullableNeutralItem(out, m.getItem());
        out.writeInt(m.getVersion());
        if (includeResponseExtensions) {
            writeResponseExtensionsIfSupported(out, m.getScope(), m.isSuccess(), m.getFailReason(), includeV2Fields);
        }
    }

    private static void encodeQuerySlotVersionsRequest(DataOutputStream out, QuerySlotVersionsRequest m,
                                                       boolean includeV2Fields) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        writeInventoryAccessIfSupported(out, m.getAccess(), includeV2Fields);
    }

    private static void encodeSlotVersionsResponse(DataOutputStream out, SlotVersionsResponse m,
                                                   boolean includeV2Fields) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        List<Integer> versions = m.getVersions();
        out.writeInt(versions != null ? versions.size() : 0);
        if (versions != null) {
            for (Integer version : versions) {
                out.writeInt(version != null ? version : 0);
            }
        }
        writeResponseExtensionsIfSupported(out, m.getScope(), m.isSuccess(), m.getFailReason(), includeV2Fields);
    }

    private static void encodeQuerySlotsRequest(DataOutputStream out, QuerySlotsRequest m,
                                                boolean includeV2Fields) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        List<Integer> slots = m.getSlots();
        out.writeInt(slots != null ? slots.size() : 0);
        if (slots != null) {
            for (int slot : slots) {
                out.writeInt(slot);
            }
        }
        writeInventoryAccessIfSupported(out, m.getAccess(), includeV2Fields);
    }

    private static void encodeSlotsStateResponse(DataOutputStream out, SlotsStateResponse m,
                                                 boolean includeV2Fields) throws IOException {
        BinaryIO.writeString(out, m.getRequestId());
        List<SlotStateResponse> slots = m.getSlots();
        out.writeInt(slots != null ? slots.size() : 0);
        if (slots != null) {
            for (SlotStateResponse slot : slots) {
                encodeSlotStateResponse(out, slot, includeV2Fields, false);
            }
        }
        writeResponseExtensionsIfSupported(out, m.getScope(), m.isSuccess(), m.getFailReason(), includeV2Fields);
    }

    private static void encodePutItemRequest(DataOutputStream out, PutItemRequest m,
                                             boolean includeV2Fields) throws IOException {
        out.writeInt(m.getSlot());
        BinaryIO.writeNullableNeutralItem(out, m.getItem());
        out.writeInt(m.getExpectedVersion());
        BinaryIO.writeString(out, m.getRequestId());
        BinaryIO.writeString(out, m.getPlayerUuid());
        BinaryIO.writeString(out, m.getPlayerName());
        writeInventoryAccessIfSupported(out, m.getAccess(), includeV2Fields);
    }

    private static void encodePutItemResponse(DataOutputStream out, PutItemResponse m,
                                              boolean includeV2Fields) throws IOException {
        out.writeBoolean(m.isSuccess());
        out.writeInt(m.getSlot());
        BinaryIO.writeNullableNeutralItem(out, m.getCurrentItem());
        BinaryIO.writeString(out, m.getFailReason());
        out.writeLong(m.getNewTimestamp());
        out.writeInt(m.getNewVersion());
        BinaryIO.writeString(out, m.getRequestId());
        writeInventoryScopeIfSupported(out, m.getScope(), includeV2Fields);
    }

    private static void encodeTakeItemRequest(DataOutputStream out, TakeItemRequest m,
                                              boolean includeV2Fields) throws IOException {
        out.writeInt(m.getSlot());
        BinaryIO.writeString(out, m.getExpectedItemId());
        out.writeInt(m.getExpectedVersion());
        out.writeInt(m.getRequestCount());
        BinaryIO.writeString(out, m.getRequestId());
        BinaryIO.writeString(out, m.getPlayerUuid());
        BinaryIO.writeString(out, m.getPlayerName());
        writeInventoryAccessIfSupported(out, m.getAccess(), includeV2Fields);
    }

    private static void encodeTakeItemResponse(DataOutputStream out, TakeItemResponse m,
                                               boolean includeV2Fields) throws IOException {
        out.writeBoolean(m.isSuccess());
        out.writeInt(m.getSlot());
        BinaryIO.writeNullableNeutralItem(out, m.getCurrentItem());
        BinaryIO.writeString(out, m.getFailReason());
        out.writeLong(m.getNewTimestamp());
        out.writeInt(m.getNewVersion());
        BinaryIO.writeNullableNeutralItem(out, m.getItemsToGive());
        BinaryIO.writeString(out, m.getRequestId());
        writeInventoryScopeIfSupported(out, m.getScope(), includeV2Fields);
    }

    private static void encodeSwapItemRequest(DataOutputStream out, SwapItemRequest m,
                                              boolean includeV2Fields) throws IOException {
        out.writeInt(m.getSlot());
        BinaryIO.writeNullableNeutralItem(out, m.getNewItem());
        out.writeInt(m.getExpectedVersion());
        BinaryIO.writeString(out, m.getExpectedItemId());
        out.writeInt(m.getTakeCount());
        out.writeBoolean(m.isBoundedMerge());
        BinaryIO.writeString(out, m.getRequestId());
        BinaryIO.writeString(out, m.getPlayerUuid());
        BinaryIO.writeString(out, m.getPlayerName());
        writeInventoryAccessIfSupported(out, m.getAccess(), includeV2Fields);
    }

    private static void encodeSwapItemResponse(DataOutputStream out, SwapItemResponse m,
                                               boolean includeV2Fields) throws IOException {
        out.writeBoolean(m.isSuccess());
        out.writeInt(m.getSlot());
        BinaryIO.writeNullableNeutralItem(out, m.getCurrentItem());
        BinaryIO.writeNullableNeutralItem(out, m.getTakenItem());
        out.writeInt(m.getNewVersion());
        BinaryIO.writeString(out, m.getFailReason());
        BinaryIO.writeString(out, m.getRequestId());
        writeInventoryScopeIfSupported(out, m.getScope(), includeV2Fields);
    }

    private static void encodePushUpdate(DataOutputStream out, PushUpdate m, boolean includeV2Fields) throws IOException {
        List<Integer> slots = m.getChangedSlots();
        out.writeLong(m.getTimestamp());
        out.writeInt(slots != null ? slots.size() : 0);
        if (slots != null) {
            for (int slot : slots) {
                out.writeInt(slot);
            }
        }
        writeInventoryScopeIfSupported(out, m.getScope(), includeV2Fields);
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
        if (in.available() > 0) {
            response.setVersion(BinaryIO.readString(in));
        } else {
            response.setVersion("1");
        }
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
        request.setAccess(readInventoryAccessIfPresent(in));
        return request;
    }

    private static QuerySlotVersionResponse decodeQuerySlotVersionResponse(DataInputStream in) throws IOException {
        QuerySlotVersionResponse response = new QuerySlotVersionResponse(BinaryIO.readString(in), in.readInt(), in.readInt());
        response.setScope(readInventoryScopeIfPresent(in));
        readQueryResultIfPresent(in, response::setSuccess, response::setFailReason);
        return response;
    }

    private static QuerySlotStateRequest decodeQuerySlotStateRequest(DataInputStream in) throws IOException {
        QuerySlotStateRequest request = new QuerySlotStateRequest(BinaryIO.readString(in), in.readInt());
        request.setAccess(readInventoryAccessIfPresent(in));
        return request;
    }

    private static SlotStateResponse decodeSlotStateResponse(DataInputStream in) throws IOException {
        SlotStateResponse response = new SlotStateResponse(BinaryIO.readString(in), in.readInt(), BinaryIO.readNullableNeutralItem(in), in.readInt());
        response.setScope(readInventoryScopeIfPresent(in));
        readQueryResultIfPresent(in, response::setSuccess, response::setFailReason);
        return response;
    }

    private static SlotStateResponse decodeSlotStateResponseWithoutScope(DataInputStream in) throws IOException {
        return new SlotStateResponse(BinaryIO.readString(in), in.readInt(), BinaryIO.readNullableNeutralItem(in), in.readInt());
    }

    private static QuerySlotVersionsRequest decodeQuerySlotVersionsRequest(DataInputStream in) throws IOException {
        QuerySlotVersionsRequest request = new QuerySlotVersionsRequest(BinaryIO.readString(in));
        request.setAccess(readInventoryAccessIfPresent(in));
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
        response.setScope(readInventoryScopeIfPresent(in));
        readQueryResultIfPresent(in, response::setSuccess, response::setFailReason);
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
        request.setAccess(readInventoryAccessIfPresent(in));
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
        response.setScope(readInventoryScopeIfPresent(in));
        readQueryResultIfPresent(in, response::setSuccess, response::setFailReason);
        return response;
    }

    private static PutItemRequest decodePutItemRequest(DataInputStream in) throws IOException {
        PutItemRequest request = new PutItemRequest();
        request.setSlot(in.readInt());
        request.setItem(BinaryIO.readNullableNeutralItem(in));
        request.setExpectedVersion(in.readInt());
        request.setRequestId(BinaryIO.readString(in));
        request.setPlayerUuid(BinaryIO.readString(in));
        request.setPlayerName(BinaryIO.readString(in));
        request.setAccess(readInventoryAccessIfPresent(in));
        return request;
    }

    private static PutItemResponse decodePutItemResponse(DataInputStream in) throws IOException {
        PutItemResponse response = new PutItemResponse();
        response.setSuccess(in.readBoolean());
        response.setSlot(in.readInt());
        response.setCurrentItem(BinaryIO.readNullableNeutralItem(in));
        response.setFailReason(BinaryIO.readString(in));
        response.setNewTimestamp(in.readLong());
        response.setNewVersion(in.readInt());
        response.setRequestId(BinaryIO.readString(in));
        response.setScope(readInventoryScopeIfPresent(in));
        return response;
    }

    private static TakeItemRequest decodeTakeItemRequest(DataInputStream in) throws IOException {
        TakeItemRequest request = new TakeItemRequest();
        request.setSlot(in.readInt());
        request.setExpectedItemId(BinaryIO.readString(in));
        request.setExpectedVersion(in.readInt());
        request.setRequestCount(in.readInt());
        request.setRequestId(BinaryIO.readString(in));
        request.setPlayerUuid(BinaryIO.readString(in));
        request.setPlayerName(BinaryIO.readString(in));
        request.setAccess(readInventoryAccessIfPresent(in));
        return request;
    }

    private static TakeItemResponse decodeTakeItemResponse(DataInputStream in) throws IOException {
        TakeItemResponse response = new TakeItemResponse();
        response.setSuccess(in.readBoolean());
        response.setSlot(in.readInt());
        response.setCurrentItem(BinaryIO.readNullableNeutralItem(in));
        response.setFailReason(BinaryIO.readString(in));
        response.setNewTimestamp(in.readLong());
        response.setNewVersion(in.readInt());
        response.setItemsToGive(BinaryIO.readNullableNeutralItem(in));
        response.setRequestId(BinaryIO.readString(in));
        response.setScope(readInventoryScopeIfPresent(in));
        return response;
    }

    private static SwapItemRequest decodeSwapItemRequest(DataInputStream in) throws IOException {
        SwapItemRequest request = new SwapItemRequest();
        request.setSlot(in.readInt());
        request.setNewItem(BinaryIO.readNullableNeutralItem(in));
        request.setExpectedVersion(in.readInt());
        request.setExpectedItemId(BinaryIO.readString(in));
        request.setTakeCount(in.readInt());
        request.setBoundedMerge(in.readBoolean());
        request.setRequestId(BinaryIO.readString(in));
        request.setPlayerUuid(BinaryIO.readString(in));
        request.setPlayerName(BinaryIO.readString(in));
        request.setAccess(readInventoryAccessIfPresent(in));
        return request;
    }

    private static SwapItemResponse decodeSwapItemResponse(DataInputStream in) throws IOException {
        SwapItemResponse response = new SwapItemResponse();
        response.setSuccess(in.readBoolean());
        response.setSlot(in.readInt());
        response.setCurrentItem(BinaryIO.readNullableNeutralItem(in));
        response.setTakenItem(BinaryIO.readNullableNeutralItem(in));
        response.setNewVersion(in.readInt());
        response.setFailReason(BinaryIO.readString(in));
        response.setRequestId(BinaryIO.readString(in));
        response.setScope(readInventoryScopeIfPresent(in));
        return response;
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
        update.setScope(readInventoryScopeIfPresent(in));
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
    }

    private static void writeInventoryAccessIfSupported(DataOutputStream out, InventoryAccess access,
                                                        boolean includeV2Fields) throws IOException {
        if (includeV2Fields) {
            writeInventoryAccess(out, access);
            return;
        }
        InventoryAccess value = access != null ? access : InventoryAccess.server();
        if (value.isPlayer()) {
            throw new IOException("Protocol v1 cannot encode player inventory access");
        }
    }

    private static InventoryAccess readInventoryAccessIfPresent(DataInputStream in) throws IOException {
        if (in.available() <= 0) {
            return InventoryAccess.server();
        }
        String type = BinaryIO.readString(in);
        String ownerName = BinaryIO.readString(in);
        String token = BinaryIO.readString(in);
        String requesterUuid = BinaryIO.readString(in);
        String requesterName = BinaryIO.readString(in);
        InventoryScope.ScopeType scopeType = InventoryScope.ScopeType.SERVER;
        if (type != null && !type.isBlank()) {
            scopeType = InventoryScope.ScopeType.valueOf(type.trim().toUpperCase(java.util.Locale.ROOT));
        }
        return scopeType == InventoryScope.ScopeType.PLAYER
                ? InventoryAccess.playerSession(ownerName, token, requesterUuid, requesterName,
                        null, 0)
                : InventoryAccess.server();
    }

    private static void writeInventoryScope(DataOutputStream out, InventoryScope scope) throws IOException {
        InventoryScope value = scope != null ? scope : InventoryScope.server();
        BinaryIO.writeString(out, value.typeName());
        BinaryIO.writeString(out, value.getScopeId());
    }

    private static void writeInventoryScopeIfSupported(DataOutputStream out, InventoryScope scope,
                                                       boolean includeV2Fields) throws IOException {
        InventoryScope value = scope != null ? scope : InventoryScope.server();
        if (includeV2Fields) {
            writeInventoryScope(out, value);
            return;
        }
        if (value.isPlayer()) {
            throw new IOException("Protocol v1 cannot encode player inventory scope");
        }
    }

    private static void writeResponseExtensionsIfSupported(DataOutputStream out, InventoryScope scope,
                                                           boolean success, String failReason,
                                                           boolean includeV2Fields) throws IOException {
        if (includeV2Fields) {
            writeInventoryScope(out, scope);
            out.writeBoolean(success);
            BinaryIO.writeString(out, failReason);
            return;
        }
        writeInventoryScopeIfSupported(out, scope, false);
        if (!success || (failReason != null && !failReason.isBlank())) {
            throw new IOException("Protocol v1 cannot encode query failure metadata");
        }
    }

    private static InventoryScope readInventoryScopeIfPresent(DataInputStream in) throws IOException {
        if (in.available() <= 0) {
            return InventoryScope.server();
        }
        return readInventoryScope(in);
    }

    private static InventoryScope readInventoryScope(DataInputStream in) throws IOException {
        return InventoryScope.of(BinaryIO.readString(in), BinaryIO.readString(in));
    }

    private static void requireProtocolV2(boolean includeV2Fields, String label) throws IOException {
        if (!includeV2Fields) {
            throw new IOException("Protocol v1 cannot encode " + label);
        }
    }

    private interface BooleanSetter {
        void set(boolean value);
    }

    private interface StringSetter {
        void set(String value);
    }

    private static void readQueryResultIfPresent(DataInputStream in, BooleanSetter successSetter,
                                                 StringSetter failReasonSetter) throws IOException {
        if (in.available() <= 0) {
            return;
        }
        successSetter.set(in.readBoolean());
        failReasonSetter.set(BinaryIO.readString(in));
    }
}
