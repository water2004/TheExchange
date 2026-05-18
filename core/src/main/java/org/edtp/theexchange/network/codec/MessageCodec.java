package org.edtp.theexchange.network.codec;

import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.network.protocol.Frame;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.*;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes/decodes message POJOs to/from byte arrays using MessagePack.
 * Manual codec (no reflection/annotation) for predictable behavior.
 */
public final class MessageCodec {

    private MessageCodec() {}

    // ---- Encode ----

    public static byte[] encodeMessage(Object msg) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             MessagePacker p = MessagePack.newDefaultPacker(bos)) {
            if (msg instanceof AuthRequest m) encodeAuthRequest(p, m);
            else if (msg instanceof AuthResponse m) encodeAuthResponse(p, m);
            else if (msg instanceof Heartbeat m) encodeHeartbeat(p, m);
            else if (msg instanceof QueryTimestampRequest m) encodeQueryTimestampRequest(p, m);
            else if (msg instanceof QueryTimestampResponse m) encodeQueryTimestampResponse(p, m);
            else if (msg instanceof QueryItemsRequest m) encodeQueryItemsRequest(p, m);
            else if (msg instanceof QueryItemsResponse m) encodeQueryItemsResponse(p, m);
            else if (msg instanceof PutItemRequest m) encodePutItemRequest(p, m);
            else if (msg instanceof PutItemResponse m) encodePutItemResponse(p, m);
            else if (msg instanceof TakeItemRequest m) encodeTakeItemRequest(p, m);
            else if (msg instanceof TakeItemResponse m) encodeTakeItemResponse(p, m);
            else if (msg instanceof PushUpdate m) encodePushUpdate(p, m);
            else if (msg instanceof ErrorMessage m) encodeError(p, m);
            else throw new IllegalArgumentException("Unknown message type: " + msg.getClass());
            p.close();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode " + msg.getClass().getSimpleName(), e);
        }
    }

    // ---- Decode ----

    public static Object decodeMessage(FrameType type, byte[] payload) {
        if (payload == null || payload.length == 0) return null;
        try (MessageUnpacker u = MessagePack.newDefaultUnpacker(payload)) {
            return switch (type) {
                case AUTH_REQUEST -> decodeAuthRequest(u);
                case AUTH_RESPONSE -> decodeAuthResponse(u);
                case HEARTBEAT -> decodeHeartbeat(u);
                case QUERY_TIMESTAMP -> decodeQueryTimestampRequest(u);
                case TIMESTAMP_RESPONSE -> decodeQueryTimestampResponse(u);
                case QUERY_ITEMS -> decodeQueryItemsRequest(u);
                case ITEMS_RESPONSE -> decodeQueryItemsResponse(u);
                case PUT_ITEM -> decodePutItemRequest(u);
                case PUT_ITEM_RESPONSE -> decodePutItemResponse(u);
                case TAKE_ITEM -> decodeTakeItemRequest(u);
                case TAKE_ITEM_RESPONSE -> decodeTakeItemResponse(u);
                case PUSH_UPDATE -> decodePushUpdate(u);
                case ERROR -> decodeError(u);
            };
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode " + type, e);
        }
    }

    // ===== Encoding helpers =====

    private static void encodeAuthRequest(MessagePacker p, AuthRequest m) throws IOException {
        p.packMapHeader(4);
        p.packString("sn"); p.packString(m.getServerName());
        p.packString("pw"); p.packString(m.getPassword());
        p.packString("v");  p.packString(m.getVersion());
        p.packString("mv"); p.packString(m.getMcVersion());
    }

    private static void encodeAuthResponse(MessagePacker p, AuthResponse m) throws IOException {
        p.packMapHeader(5);
        p.packString("ok"); p.packBoolean(m.isSuccess());
        p.packString("msg"); p.packString(orEmpty(m.getMessage()));
        p.packString("sn"); p.packString(orEmpty(m.getServerName()));
        p.packString("mv"); p.packString(orEmpty(m.getMcVersion()));
        p.packString("ts"); p.packLong(m.getLastModifiedTimestamp());
    }

    private static void encodeHeartbeat(MessagePacker p, Heartbeat m) throws IOException {
        p.packMapHeader(2);
        p.packString("r"); p.packBoolean(m.isReply());
        p.packString("t"); p.packLong(m.getTimestamp());
    }

    private static void encodeQueryTimestampRequest(MessagePacker p, QueryTimestampRequest m) throws IOException {
        p.packMapHeader(1);
        p.packString("ct"); p.packLong(m.getCachedTimestamp());
    }

    private static void encodeQueryTimestampResponse(MessagePacker p, QueryTimestampResponse m) throws IOException {
        p.packMapHeader(2);
        p.packString("t"); p.packLong(m.getCurrentTimestamp());
        p.packString("ch"); p.packBoolean(m.isChanged());
    }

    private static void encodeQueryItemsRequest(MessagePacker p, QueryItemsRequest m) throws IOException {
        p.packMapHeader(2);
        p.packString("o"); p.packInt(m.getOffset());
        p.packString("l"); p.packInt(m.getLimit());
    }

    private static void encodeQueryItemsResponse(MessagePacker p, QueryItemsResponse m) throws IOException {
        p.packMapHeader(4);
        p.packString("items");
        List<NeutralItem> items = m.getItems();
        p.packArrayHeader(items != null ? items.size() : 0);
        if (items != null) {
            for (NeutralItem item : items) encodeNeutralItem(p, item);
        }
        p.packString("ts"); p.packInt(m.getTotalSlots());
        p.packString("mt"); p.packLong(m.getTimestamp());
        p.packString("sv"); p.packString(orEmpty(m.getServerVersion()));
    }

    private static void encodePutItemRequest(MessagePacker p, PutItemRequest m) throws IOException {
        p.packMapHeader(5);
        p.packString("s"); p.packInt(m.getSlot());
        p.packString("item"); encodeNeutralItem(p, m.getItem());
        p.packString("rid"); p.packString(m.getRequestId());
        p.packString("uid"); p.packString(m.getPlayerUuid());
        p.packString("pn"); p.packString(m.getPlayerName());
    }

    private static void encodePutItemResponse(MessagePacker p, PutItemResponse m) throws IOException {
        p.packMapHeader(6);
        p.packString("ok"); p.packBoolean(m.isSuccess());
        p.packString("s"); p.packInt(m.getSlot());
        p.packString("ci"); encodeNeutralItemOrNull(p, m.getCurrentItem());
        p.packString("fr"); p.packString(orEmpty(m.getFailReason()));
        p.packString("nt"); p.packLong(m.getNewTimestamp());
        p.packString("nv"); p.packInt(m.getNewVersion());
    }

    private static void encodeTakeItemRequest(MessagePacker p, TakeItemRequest m) throws IOException {
        p.packMapHeader(7);
        p.packString("s"); p.packInt(m.getSlot());
        p.packString("ei"); p.packString(m.getExpectedItemId());
        p.packString("ev"); p.packInt(m.getExpectedVersion());
        p.packString("rc"); p.packInt(m.getRequestCount());
        p.packString("rid"); p.packString(m.getRequestId());
        p.packString("uid"); p.packString(m.getPlayerUuid());
        p.packString("pn"); p.packString(m.getPlayerName());
    }

    private static void encodeTakeItemResponse(MessagePacker p, TakeItemResponse m) throws IOException {
        p.packMapHeader(7);
        p.packString("ok"); p.packBoolean(m.isSuccess());
        p.packString("s"); p.packInt(m.getSlot());
        p.packString("ci"); encodeNeutralItemOrNull(p, m.getCurrentItem());
        p.packString("fr"); p.packString(orEmpty(m.getFailReason()));
        p.packString("nt"); p.packLong(m.getNewTimestamp());
        p.packString("nv"); p.packInt(m.getNewVersion());
        p.packString("itg"); encodeNeutralItemOrNull(p, m.getItemsToGive());
    }

    private static void encodePushUpdate(MessagePacker p, PushUpdate m) throws IOException {
        p.packMapHeader(2);
        p.packString("cs");
        List<Integer> slots = m.getChangedSlots();
        p.packArrayHeader(slots != null ? slots.size() : 0);
        if (slots != null) {
            for (int s : slots) p.packInt(s);
        }
        p.packString("t"); p.packLong(m.getTimestamp());
    }

    private static void encodeError(MessagePacker p, ErrorMessage m) throws IOException {
        p.packMapHeader(2);
        p.packString("c"); p.packInt(m.getCode());
        p.packString("m"); p.packString(m.getMessage());
    }

    private static void encodeNeutralItem(MessagePacker p, NeutralItem item) throws IOException {
        if (item == null) { p.packNil(); return; }
        p.packMapHeader(6);
        p.packString("id"); p.packString(orEmpty(item.getItemId()));
        p.packString("ct"); p.packInt(item.getCount());
        p.packString("dn"); p.packString(orEmpty(item.getDisplayName()));
        if (item.getExtraData() != null) {
            p.packString("ed"); p.packBinaryHeader(item.getExtraData().length); p.writePayload(item.getExtraData());
        } else {
            p.packString("ed"); p.packBinaryHeader(0); p.writePayload(new byte[0]);
        }
        p.packString("ic"); p.packBoolean(item.isIncompatible());
        p.packString("sv"); p.packString(orEmpty(item.getSourceVersion()));
    }

    private static void encodeNeutralItemOrNull(MessagePacker p, NeutralItem item) throws IOException {
        if (item == null) p.packNil();
        else encodeNeutralItem(p, item);
    }

    // ===== Decoding helpers =====

    private static AuthRequest decodeAuthRequest(MessageUnpacker u) throws IOException {
        AuthRequest m = new AuthRequest();
        int size = u.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            switch (u.unpackString()) {
                case "sn" -> m.setServerName(u.unpackString());
                case "pw" -> m.setPassword(u.unpackString());
                case "v"  -> m.setVersion(u.unpackString());
                case "mv" -> m.setMcVersion(u.unpackString());
                default -> u.skipValue();
            }
        }
        return m;
    }

    private static AuthResponse decodeAuthResponse(MessageUnpacker u) throws IOException {
        AuthResponse m = new AuthResponse();
        int size = u.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            switch (u.unpackString()) {
                case "ok" -> m.setSuccess(u.unpackBoolean());
                case "msg" -> m.setMessage(u.unpackString());
                case "sn" -> m.setServerName(u.unpackString());
                case "mv" -> m.setMcVersion(u.unpackString());
                case "ts" -> m.setLastModifiedTimestamp(u.unpackLong());
                default -> u.skipValue();
            }
        }
        return m;
    }

    private static Heartbeat decodeHeartbeat(MessageUnpacker u) throws IOException {
        Heartbeat m = new Heartbeat();
        int size = u.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            switch (u.unpackString()) {
                case "r" -> m.setReply(u.unpackBoolean());
                case "t" -> m.setTimestamp(u.unpackLong());
                default -> u.skipValue();
            }
        }
        return m;
    }

    private static QueryTimestampRequest decodeQueryTimestampRequest(MessageUnpacker u) throws IOException {
        QueryTimestampRequest m = new QueryTimestampRequest();
        int size = u.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            if ("ct".equals(u.unpackString())) m.setCachedTimestamp(u.unpackLong());
            else u.skipValue();
        }
        return m;
    }

    private static QueryTimestampResponse decodeQueryTimestampResponse(MessageUnpacker u) throws IOException {
        QueryTimestampResponse m = new QueryTimestampResponse();
        int size = u.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            switch (u.unpackString()) {
                case "t" -> m.setCurrentTimestamp(u.unpackLong());
                case "ch" -> m.setChanged(u.unpackBoolean());
                default -> u.skipValue();
            }
        }
        return m;
    }

    private static QueryItemsRequest decodeQueryItemsRequest(MessageUnpacker u) throws IOException {
        QueryItemsRequest m = new QueryItemsRequest();
        int size = u.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            switch (u.unpackString()) {
                case "o" -> m.setOffset(u.unpackInt());
                case "l" -> m.setLimit(u.unpackInt());
                default -> u.skipValue();
            }
        }
        return m;
    }

    private static QueryItemsResponse decodeQueryItemsResponse(MessageUnpacker u) throws IOException {
        QueryItemsResponse m = new QueryItemsResponse();
        int size = u.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            switch (u.unpackString()) {
                case "items" -> {
                    int len = u.unpackArrayHeader();
                    List<NeutralItem> items = new ArrayList<>(len);
                    for (int j = 0; j < len; j++) items.add(decodeNeutralItem(u));
                    m.setItems(items);
                }
                case "ts" -> m.setTotalSlots(u.unpackInt());
                case "mt" -> m.setTimestamp(u.unpackLong());
                case "sv" -> m.setServerVersion(u.unpackString());
                default -> u.skipValue();
            }
        }
        return m;
    }

    private static PutItemRequest decodePutItemRequest(MessageUnpacker u) throws IOException {
        PutItemRequest m = new PutItemRequest();
        int size = u.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            switch (u.unpackString()) {
                case "s" -> m.setSlot(u.unpackInt());
                case "item" -> m.setItem(decodeNeutralItem(u));
                case "rid" -> m.setRequestId(u.unpackString());
                case "uid" -> m.setPlayerUuid(u.unpackString());
                case "pn" -> m.setPlayerName(u.unpackString());
                default -> u.skipValue();
            }
        }
        return m;
    }

    private static PutItemResponse decodePutItemResponse(MessageUnpacker u) throws IOException {
        PutItemResponse m = new PutItemResponse();
        int size = u.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            try {
                switch (u.unpackString()) {
                    case "ok" -> m.setSuccess(u.unpackBoolean());
                    case "s" -> m.setSlot(u.unpackInt());
                    case "ci" -> m.setCurrentItem(decodeNullableNeutralItem(u));
                    case "fr" -> m.setFailReason(u.unpackString());
                    case "nt" -> m.setNewTimestamp(u.unpackLong());
                    case "nv" -> m.setNewVersion(u.unpackInt());
                    default -> u.skipValue();
                }
            } catch (Exception e) {
                u.skipValue();
            }
        }
        return m;
    }

    private static TakeItemRequest decodeTakeItemRequest(MessageUnpacker u) throws IOException {
        TakeItemRequest m = new TakeItemRequest();
        int size = u.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            switch (u.unpackString()) {
                case "s" -> m.setSlot(u.unpackInt());
                case "ei" -> m.setExpectedItemId(u.unpackString());
                case "ev" -> m.setExpectedVersion(u.unpackInt());
                case "rc" -> m.setRequestCount(u.unpackInt());
                case "rid" -> m.setRequestId(u.unpackString());
                case "uid" -> m.setPlayerUuid(u.unpackString());
                case "pn" -> m.setPlayerName(u.unpackString());
                default -> u.skipValue();
            }
        }
        return m;
    }

    private static TakeItemResponse decodeTakeItemResponse(MessageUnpacker u) throws IOException {
        TakeItemResponse m = new TakeItemResponse();
        int size = u.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            switch (u.unpackString()) {
                case "ok" -> m.setSuccess(u.unpackBoolean());
                case "s" -> m.setSlot(u.unpackInt());
                case "ci" -> m.setCurrentItem(decodeNullableNeutralItem(u));
                case "fr" -> m.setFailReason(u.unpackString());
                case "nt" -> m.setNewTimestamp(u.unpackLong());
                case "nv" -> m.setNewVersion(u.unpackInt());
                case "itg" -> m.setItemsToGive(decodeNullableNeutralItem(u));
                default -> u.skipValue();
            }
        }
        return m;
    }

    private static PushUpdate decodePushUpdate(MessageUnpacker u) throws IOException {
        PushUpdate m = new PushUpdate();
        int size = u.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            switch (u.unpackString()) {
                case "cs" -> {
                    int len = u.unpackArrayHeader();
                    List<Integer> slots = new ArrayList<>(len);
                    for (int j = 0; j < len; j++) slots.add(u.unpackInt());
                    m.setChangedSlots(slots);
                }
                case "t" -> m.setTimestamp(u.unpackLong());
                default -> u.skipValue();
            }
        }
        return m;
    }

    private static ErrorMessage decodeError(MessageUnpacker u) throws IOException {
        ErrorMessage m = new ErrorMessage();
        int size = u.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            switch (u.unpackString()) {
                case "c" -> m.setCode(u.unpackInt());
                case "m" -> m.setMessage(u.unpackString());
                default -> u.skipValue();
            }
        }
        return m;
    }

    private static NeutralItem decodeNeutralItem(MessageUnpacker u) throws IOException {
        NeutralItem item = new NeutralItem();
        int size = u.unpackMapHeader();
        for (int i = 0; i < size; i++) {
            switch (u.unpackString()) {
                case "id" -> item.setItemId(u.unpackString());
                case "ct" -> item.setCount(u.unpackInt());
                case "dn" -> item.setDisplayName(u.unpackString());
                case "ed" -> {
                    int len = u.unpackBinaryHeader();
                    item.setExtraData(u.readPayload(len));
                }
                case "ic" -> item.setIncompatible(u.unpackBoolean());
                case "sv" -> item.setSourceVersion(u.unpackString());
                default -> u.skipValue();
            }
        }
        return item;
    }

    private static NeutralItem decodeNullableNeutralItem(MessageUnpacker u) throws IOException {
        if (u.tryUnpackNil()) return null;
        return decodeNeutralItem(u);
    }

    private static String orEmpty(String s) { return s != null ? s : ""; }
}
