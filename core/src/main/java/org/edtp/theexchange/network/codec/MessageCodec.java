package org.edtp.theexchange.network.codec;

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

    private MessageCodec() {}

    public static byte[] encodeMessage(Object msg) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bos)) {
            if (msg instanceof AuthRequest m) encodeAuthRequest(out, m);
            else if (msg instanceof AuthResponse m) encodeAuthResponse(out, m);
            else if (msg instanceof Heartbeat m) encodeHeartbeat(out, m);
            else if (msg instanceof QueryTimestampRequest m) encodeQueryTimestampRequest(out, m);
            else if (msg instanceof QueryTimestampResponse m) encodeQueryTimestampResponse(out, m);
            else if (msg instanceof QueryItemsRequest m) encodeQueryItemsRequest(out, m);
            else if (msg instanceof QueryItemsResponse m) encodeQueryItemsResponse(out, m);
            else if (msg instanceof PutItemRequest m) encodePutItemRequest(out, m);
            else if (msg instanceof PutItemResponse m) encodePutItemResponse(out, m);
            else if (msg instanceof TakeItemRequest m) encodeTakeItemRequest(out, m);
            else if (msg instanceof TakeItemResponse m) encodeTakeItemResponse(out, m);
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
        if (payload == null || payload.length == 0) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            return switch (type) {
                case AUTH_REQUEST -> decodeAuthRequest(in);
                case AUTH_RESPONSE -> decodeAuthResponse(in);
                case HEARTBEAT -> decodeHeartbeat(in);
                case QUERY_TIMESTAMP -> decodeQueryTimestampRequest(in);
                case TIMESTAMP_RESPONSE -> decodeQueryTimestampResponse(in);
                case QUERY_ITEMS -> decodeQueryItemsRequest(in);
                case ITEMS_RESPONSE -> decodeQueryItemsResponse(in);
                case PUT_ITEM -> decodePutItemRequest(in);
                case PUT_ITEM_RESPONSE -> decodePutItemResponse(in);
                case TAKE_ITEM -> decodeTakeItemRequest(in);
                case TAKE_ITEM_RESPONSE -> decodeTakeItemResponse(in);
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
    }

    private static void encodeHeartbeat(DataOutputStream out, Heartbeat m) throws IOException {
        out.writeBoolean(m.isReply());
        out.writeLong(m.getTimestamp());
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

    private static void encodePutItemRequest(DataOutputStream out, PutItemRequest m) throws IOException {
        out.writeInt(m.getSlot());
        BinaryIO.writeNullableNeutralItem(out, m.getItem());
        out.writeInt(m.getExpectedVersion());
        BinaryIO.writeString(out, m.getRequestId());
        BinaryIO.writeString(out, m.getPlayerUuid());
        BinaryIO.writeString(out, m.getPlayerName());
    }

    private static void encodePutItemResponse(DataOutputStream out, PutItemResponse m) throws IOException {
        out.writeBoolean(m.isSuccess());
        out.writeInt(m.getSlot());
        BinaryIO.writeNullableNeutralItem(out, m.getCurrentItem());
        BinaryIO.writeString(out, m.getFailReason());
        out.writeLong(m.getNewTimestamp());
        out.writeInt(m.getNewVersion());
    }

    private static void encodeTakeItemRequest(DataOutputStream out, TakeItemRequest m) throws IOException {
        out.writeInt(m.getSlot());
        BinaryIO.writeString(out, m.getExpectedItemId());
        out.writeInt(m.getExpectedVersion());
        out.writeInt(m.getRequestCount());
        BinaryIO.writeString(out, m.getRequestId());
        BinaryIO.writeString(out, m.getPlayerUuid());
        BinaryIO.writeString(out, m.getPlayerName());
    }

    private static void encodeTakeItemResponse(DataOutputStream out, TakeItemResponse m) throws IOException {
        out.writeBoolean(m.isSuccess());
        out.writeInt(m.getSlot());
        BinaryIO.writeNullableNeutralItem(out, m.getCurrentItem());
        BinaryIO.writeString(out, m.getFailReason());
        out.writeLong(m.getNewTimestamp());
        out.writeInt(m.getNewVersion());
        BinaryIO.writeNullableNeutralItem(out, m.getItemsToGive());
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
        return new AuthResponse(in.readBoolean(), BinaryIO.readString(in),
                BinaryIO.readString(in), BinaryIO.readString(in), in.readLong());
    }

    private static Heartbeat decodeHeartbeat(DataInputStream in) throws IOException {
        return new Heartbeat(in.readBoolean(), in.readLong());
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
        int size = in.readInt();
        List<NeutralItem> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            items.add(BinaryIO.readNullableNeutralItem(in));
        }
        return new QueryItemsResponse(items, totalSlots, timestamp, serverVersion);
    }

    private static PutItemRequest decodePutItemRequest(DataInputStream in) throws IOException {
        PutItemRequest request = new PutItemRequest();
        request.setSlot(in.readInt());
        request.setItem(BinaryIO.readNullableNeutralItem(in));
        request.setExpectedVersion(in.readInt());
        request.setRequestId(BinaryIO.readString(in));
        request.setPlayerUuid(BinaryIO.readString(in));
        request.setPlayerName(BinaryIO.readString(in));
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
        return response;
    }

    private static PushUpdate decodePushUpdate(DataInputStream in) throws IOException {
        PushUpdate update = new PushUpdate();
        update.setTimestamp(in.readLong());
        int size = in.readInt();
        List<Integer> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(in.readInt());
        }
        update.setChangedSlots(slots);
        return update;
    }

    private static ErrorMessage decodeError(DataInputStream in) throws IOException {
        return new ErrorMessage(in.readInt(), BinaryIO.readString(in));
    }
}
