package org.edtp.theexchange.network.codec;

import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.AuthRequest;
import org.edtp.theexchange.network.protocol.messages.AuthResponse;
import org.edtp.theexchange.network.protocol.messages.PushUpdate;
import org.edtp.theexchange.network.protocol.messages.PlayerInventoryAccessRequest;
import org.edtp.theexchange.network.protocol.messages.PlayerInventoryAccessResponse;
import org.edtp.theexchange.network.protocol.messages.PutItemRequest;
import org.edtp.theexchange.network.protocol.messages.PutItemResponse;
import org.edtp.theexchange.network.protocol.messages.QuerySlotStateRequest;
import org.edtp.theexchange.network.protocol.messages.QuerySlotVersionRequest;
import org.edtp.theexchange.network.protocol.messages.QuerySlotVersionResponse;
import org.edtp.theexchange.network.protocol.messages.QuerySlotsRequest;
import org.edtp.theexchange.network.protocol.messages.SlotStateResponse;
import org.edtp.theexchange.network.protocol.messages.SlotVersionsResponse;
import org.edtp.theexchange.network.protocol.messages.SlotsStateResponse;
import org.edtp.theexchange.network.protocol.messages.SwapItemRequest;
import org.edtp.theexchange.network.protocol.messages.SwapItemResponse;
import org.edtp.theexchange.network.protocol.messages.TakeItemRequest;
import org.edtp.theexchange.network.protocol.messages.TakeItemResponse;
import org.edtp.theexchange.util.BinaryIO;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageCodecPlayerInventoryTest {

    @Test
    void authResponseRoundTripsProtocolVersion() {
        AuthResponse response = new AuthResponse(true, "OK", "remote", "1.21.11", 123L);

        AuthResponse decoded = (AuthResponse) MessageCodec.decodeMessage(
                FrameType.AUTH_RESPONSE, MessageCodec.encodeMessage(response));

        assertEquals(AuthRequest.CURRENT_PROTOCOL_VERSION, decoded.getVersion());
    }

    @Test
    void legacyAuthResponseDecodesAsProtocolV1() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeBoolean(true);
            BinaryIO.writeString(out, "OK");
            BinaryIO.writeString(out, "legacy");
            BinaryIO.writeString(out, "1.21.11");
            out.writeLong(123L);
        }

        AuthResponse decoded = (AuthResponse) MessageCodec.decodeMessage(
                FrameType.AUTH_RESPONSE, bytes.toByteArray());

        assertEquals("1", decoded.getVersion());
    }

    @Test
    void authResponseCanEncodeProtocolV1Shape() {
        AuthResponse response = new AuthResponse(true, "OK", "legacy", "1.21.11", 123L);

        AuthResponse decoded = (AuthResponse) MessageCodec.decodeMessage(
                FrameType.AUTH_RESPONSE, MessageCodec.encodeMessage(response, 1));

        assertTrue(decoded.isSuccess());
        assertEquals("legacy", decoded.getServerName());
        assertEquals("1", decoded.getVersion());
    }

    @Test
    void queryRequestRoundTripsPlayerAccess() {
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        QuerySlotsRequest request = new QuerySlotsRequest("req-1", List.of(0, 1),
                InventoryAccess.playerSession("Steve", "token-123", "viewer-uuid", "Viewer",
                        scope, 12345L));

        QuerySlotsRequest decoded = (QuerySlotsRequest) MessageCodec.decodeMessage(
                FrameType.QUERY_SLOTS, MessageCodec.encodeMessage(request));

        assertEquals("req-1", decoded.getRequestId());
        assertEquals(List.of(0, 1), decoded.getSlots());
        assertTrue(decoded.getAccess().isPlayer());
        assertEquals("Steve", decoded.getAccess().ownerName());
        assertEquals("token-123", decoded.getAccess().token());
        assertEquals("viewer-uuid", decoded.getAccess().requesterUuid());
        assertEquals("Viewer", decoded.getAccess().requesterName());
        assertNull(decoded.getAccess().effectiveScope());
    }

    @Test
    void playerAccessHandshakeRoundTripsPasswordAndIssuedToken() {
        PlayerInventoryAccessRequest request = new PlayerInventoryAccessRequest(
                "access-req", "Steve", "secret", "viewer-uuid", "Viewer");

        PlayerInventoryAccessRequest decodedRequest = (PlayerInventoryAccessRequest) MessageCodec.decodeMessage(
                FrameType.PLAYER_INVENTORY_ACCESS, MessageCodec.encodeMessage(request));

        assertEquals("access-req", decodedRequest.getRequestId());
        assertEquals("Steve", decodedRequest.getOwnerName());
        assertEquals("secret", decodedRequest.getPassword());
        assertEquals("viewer-uuid", decodedRequest.getRequesterUuid());
        assertEquals("Viewer", decodedRequest.getRequesterName());

        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        PlayerInventoryAccessResponse response = PlayerInventoryAccessResponse.success(
                "access-req", "Steve", "token-123", scope, 12345L, 300_000L);
        PlayerInventoryAccessResponse decodedResponse = (PlayerInventoryAccessResponse) MessageCodec.decodeMessage(
                FrameType.PLAYER_INVENTORY_ACCESS_RESPONSE, MessageCodec.encodeMessage(response));

        assertTrue(decodedResponse.isSuccess());
        assertEquals("token-123", decodedResponse.getToken());
        assertEquals(scope, decodedResponse.getScope());
        assertEquals(12345L, decodedResponse.getExpiresAt());
        assertEquals(300_000L, decodedResponse.getSessionTtlMillis());
    }

    @Test
    void serverQueryRequestCanEncodeProtocolV1Shape() throws Exception {
        QuerySlotsRequest request = new QuerySlotsRequest("req-legacy", List.of(0, 1), InventoryAccess.server());
        byte[] payload = MessageCodec.encodeMessage(request, 1);

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            assertEquals("req-legacy", BinaryIO.readString(in));
            assertEquals(2, in.readInt());
            assertEquals(0, in.readInt());
            assertEquals(1, in.readInt());
            assertEquals(0, in.available(), "v1 query request must not include InventoryAccess");
        }

        QuerySlotsRequest decoded = (QuerySlotsRequest) MessageCodec.decodeMessage(FrameType.QUERY_SLOTS, payload);
        assertEquals(InventoryAccess.server(), decoded.getAccess());
    }

    @Test
    void legacySingleSlotQueriesDecodeAsServerScope() throws Exception {
        byte[] versionRequest = MessageCodec.encodeMessage(
                new QuerySlotVersionRequest("version-req", 4, InventoryAccess.server()), 1);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(versionRequest))) {
            assertEquals("version-req", BinaryIO.readString(in));
            assertEquals(4, in.readInt());
            assertEquals(0, in.available(), "v1 slot version request must not include access");
        }
        QuerySlotVersionRequest decodedVersionRequest = (QuerySlotVersionRequest) MessageCodec.decodeMessage(
                FrameType.QUERY_SLOT_VERSION, versionRequest);
        assertEquals(InventoryAccess.server(), decodedVersionRequest.getAccess());

        byte[] stateRequest = MessageCodec.encodeMessage(
                new QuerySlotStateRequest("state-req", 5, InventoryAccess.server()), 1);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(stateRequest))) {
            assertEquals("state-req", BinaryIO.readString(in));
            assertEquals(5, in.readInt());
            assertEquals(0, in.available(), "v1 slot state request must not include access");
        }
        QuerySlotStateRequest decodedStateRequest = (QuerySlotStateRequest) MessageCodec.decodeMessage(
                FrameType.QUERY_SLOT_STATE, stateRequest);
        assertEquals(InventoryAccess.server(), decodedStateRequest.getAccess());

        byte[] versionResponse = MessageCodec.encodeMessage(
                new QuerySlotVersionResponse("version-resp", 4, 9, InventoryScope.server()), 1);
        QuerySlotVersionResponse decodedVersionResponse = (QuerySlotVersionResponse) MessageCodec.decodeMessage(
                FrameType.SLOT_VERSION_RESPONSE, versionResponse);
        assertEquals(InventoryScope.server(), decodedVersionResponse.getScope());
        assertTrue(decodedVersionResponse.isSuccess());
        assertNull(decodedVersionResponse.getFailReason());

        byte[] stateResponse = MessageCodec.encodeMessage(
                new SlotStateResponse("state-resp", 5, sampleItem(), 7, InventoryScope.server()), 1);
        SlotStateResponse decodedStateResponse = (SlotStateResponse) MessageCodec.decodeMessage(
                FrameType.SLOT_STATE_RESPONSE, stateResponse);
        assertEquals(InventoryScope.server(), decodedStateResponse.getScope());
        assertTrue(decodedStateResponse.isSuccess());
        assertNull(decodedStateResponse.getFailReason());
    }

    @Test
    void playerQueryRequestCannotEncodeProtocolV1Shape() {
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        QuerySlotsRequest request = new QuerySlotsRequest("req-player", List.of(0),
                InventoryAccess.playerSession("Steve", "token", "viewer", "Viewer", scope, 123L));

        assertThrows(RuntimeException.class, () -> MessageCodec.encodeMessage(request, 1));
    }

    @Test
    void queryResponseRoundTripsScopeAndFailure() {
        InventoryScope scope = InventoryScope.player("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE");
        SlotVersionsResponse response = new SlotVersionsResponse("req-2", List.of(), scope);
        response.setSuccess(false);
        response.setFailReason("玩家仓库密码错误");

        SlotVersionsResponse decoded = (SlotVersionsResponse) MessageCodec.decodeMessage(
                FrameType.SLOT_VERSIONS_RESPONSE, MessageCodec.encodeMessage(response));

        assertEquals("req-2", decoded.getRequestId());
        assertFalse(decoded.isSuccess());
        assertEquals("玩家仓库密码错误", decoded.getFailReason());
        assertEquals(InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), decoded.getScope());
    }

    @Test
    void legacyQueryResponseDecodesAsSuccessfulServerScope() {
        SlotVersionsResponse original = new SlotVersionsResponse("req-legacy", List.of(1, 2), InventoryScope.server());
        byte[] payload = MessageCodec.encodeMessage(original, 1);

        SlotVersionsResponse decoded = (SlotVersionsResponse) MessageCodec.decodeMessage(
                FrameType.SLOT_VERSIONS_RESPONSE, payload);

        assertEquals("req-legacy", decoded.getRequestId());
        assertEquals(List.of(1, 2), decoded.getVersions());
        assertTrue(decoded.isSuccess());
        assertNull(decoded.getFailReason());
        assertEquals(InventoryScope.server(), decoded.getScope());
    }

    @Test
    void queryFailureCannotEncodeProtocolV1Shape() {
        SlotVersionsResponse response = new SlotVersionsResponse("req-fail", List.of(), InventoryScope.server());
        response.setSuccess(false);
        response.setFailReason("玩家仓库密码错误");

        assertThrows(RuntimeException.class, () -> MessageCodec.encodeMessage(response, 1));
    }

    @Test
    void slotsStateResponseCanEncodeProtocolV1Shape() throws Exception {
        SlotsStateResponse response = new SlotsStateResponse("req-slots",
                List.of(new SlotStateResponse("req-slots", 0, sampleItem(), 7)),
                InventoryScope.server());
        byte[] payload = MessageCodec.encodeMessage(response, 1);

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            assertEquals("req-slots", BinaryIO.readString(in));
            assertEquals(1, in.readInt());
            assertEquals("req-slots", BinaryIO.readString(in));
            assertEquals(0, in.readInt());
            assertNotNull(BinaryIO.readNullableNeutralItem(in));
            assertEquals(7, in.readInt());
            assertEquals(0, in.available(), "v1 slots response must not include scope/result metadata");
        }

        SlotsStateResponse decoded = (SlotsStateResponse) MessageCodec.decodeMessage(
                FrameType.SLOTS_STATE_RESPONSE, payload);
        assertEquals(InventoryScope.server(), decoded.getScope());
        assertTrue(decoded.isSuccess());
    }

    @Test
    void playerMutationRequestCannotEncodeProtocolV1Shape() {
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        PutItemRequest request = new PutItemRequest(0, sampleItem(), 1,
                "req-put", "uuid", "Steve",
                InventoryAccess.playerSession("Steve", "token", "uuid", "Steve", scope, 123L));

        assertThrows(RuntimeException.class, () -> MessageCodec.encodeMessage(request, 1));
    }

    @Test
    void legacyMutationMessagesDecodeAsServerScope() throws Exception {
        byte[] putRequestPayload = MessageCodec.encodeMessage(new PutItemRequest(
                0, sampleItem(), 3, "put-req", "actor", "Steve", InventoryAccess.server()), 1);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(putRequestPayload))) {
            assertEquals(0, in.readInt());
            assertNotNull(BinaryIO.readNullableNeutralItem(in));
            assertEquals(3, in.readInt());
            assertEquals("put-req", BinaryIO.readString(in));
            assertEquals("actor", BinaryIO.readString(in));
            assertEquals("Steve", BinaryIO.readString(in));
            assertEquals(0, in.available(), "v1 put request must not include access");
        }
        PutItemRequest decodedPutRequest = (PutItemRequest) MessageCodec.decodeMessage(
                FrameType.PUT_ITEM, putRequestPayload);
        assertEquals(InventoryAccess.server(), decodedPutRequest.getAccess());

        PutItemResponse putResponse = new PutItemResponse(true, 0, sampleItem(),
                null, 123L, 4, "put-req", InventoryScope.server());
        PutItemResponse decodedPutResponse = (PutItemResponse) MessageCodec.decodeMessage(
                FrameType.PUT_ITEM_RESPONSE, MessageCodec.encodeMessage(putResponse, 1));
        assertEquals(InventoryScope.server(), decodedPutResponse.getScope());

        TakeItemRequest takeRequest = new TakeItemRequest(0, "minecraft:stone", 4,
                1, "take-req", "actor", "Steve", InventoryAccess.server());
        TakeItemRequest decodedTakeRequest = (TakeItemRequest) MessageCodec.decodeMessage(
                FrameType.TAKE_ITEM, MessageCodec.encodeMessage(takeRequest, 1));
        assertEquals(InventoryAccess.server(), decodedTakeRequest.getAccess());

        TakeItemResponse takeResponse = new TakeItemResponse(true, 0, null,
                null, 124L, 5, sampleItem(), "take-req", InventoryScope.server());
        TakeItemResponse decodedTakeResponse = (TakeItemResponse) MessageCodec.decodeMessage(
                FrameType.TAKE_ITEM_RESPONSE, MessageCodec.encodeMessage(takeResponse, 1));
        assertEquals(InventoryScope.server(), decodedTakeResponse.getScope());

        SwapItemRequest swapRequest = new SwapItemRequest(0, sampleItem(), 5,
                "minecraft:dirt", 1, false, "swap-req", "actor", "Steve", InventoryAccess.server());
        SwapItemRequest decodedSwapRequest = (SwapItemRequest) MessageCodec.decodeMessage(
                FrameType.SWAP_ITEM, MessageCodec.encodeMessage(swapRequest, 1));
        assertEquals(InventoryAccess.server(), decodedSwapRequest.getAccess());

        SwapItemResponse swapResponse = new SwapItemResponse(true, 0, sampleItem(),
                sampleItem(), 6, null, "swap-req", InventoryScope.server());
        SwapItemResponse decodedSwapResponse = (SwapItemResponse) MessageCodec.decodeMessage(
                FrameType.SWAP_ITEM_RESPONSE, MessageCodec.encodeMessage(swapResponse, 1));
        assertEquals(InventoryScope.server(), decodedSwapResponse.getScope());
    }

    @Test
    void playerMutationMessagesRoundTripInProtocolV2() {
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        InventoryAccess access = InventoryAccess.playerSession(
                "Steve", "token-123", "actor", "Viewer", scope, 12345L);

        PutItemRequest decodedPutRequest = (PutItemRequest) MessageCodec.decodeMessage(
                FrameType.PUT_ITEM, MessageCodec.encodeMessage(new PutItemRequest(
                        0, sampleItem(), 1, "put-v2", "actor", "Steve", access)));
        assertTrue(decodedPutRequest.getAccess().isPlayer());
        assertEquals("Steve", decodedPutRequest.getAccess().ownerName());
        assertEquals("token-123", decodedPutRequest.getAccess().token());
        assertEquals("actor", decodedPutRequest.getAccess().requesterUuid());

        PutItemResponse decodedPutResponse = (PutItemResponse) MessageCodec.decodeMessage(
                FrameType.PUT_ITEM_RESPONSE, MessageCodec.encodeMessage(new PutItemResponse(
                        true, 0, sampleItem(), null, 1L, 2, "put-v2", scope)));
        assertEquals(scope, decodedPutResponse.getScope());

        TakeItemRequest decodedTakeRequest = (TakeItemRequest) MessageCodec.decodeMessage(
                FrameType.TAKE_ITEM, MessageCodec.encodeMessage(new TakeItemRequest(
                        0, "minecraft:stone", 2, 1, "take-v2", "actor", "Steve", access)));
        assertTrue(decodedTakeRequest.getAccess().isPlayer());

        TakeItemResponse decodedTakeResponse = (TakeItemResponse) MessageCodec.decodeMessage(
                FrameType.TAKE_ITEM_RESPONSE, MessageCodec.encodeMessage(new TakeItemResponse(
                        true, 0, null, null, 2L, 3, sampleItem(), "take-v2", scope)));
        assertEquals(scope, decodedTakeResponse.getScope());

        SwapItemRequest decodedSwapRequest = (SwapItemRequest) MessageCodec.decodeMessage(
                FrameType.SWAP_ITEM, MessageCodec.encodeMessage(new SwapItemRequest(
                        0, sampleItem(), 3, "minecraft:dirt", 1, false,
                        "swap-v2", "actor", "Steve", access)));
        assertTrue(decodedSwapRequest.getAccess().isPlayer());

        SwapItemResponse decodedSwapResponse = (SwapItemResponse) MessageCodec.decodeMessage(
                FrameType.SWAP_ITEM_RESPONSE, MessageCodec.encodeMessage(new SwapItemResponse(
                        true, 0, sampleItem(), sampleItem(), 4, null, "swap-v2", scope)));
        assertEquals(scope, decodedSwapResponse.getScope());
    }

    @Test
    void pushUpdateRoundTripsResolvedScope() {
        InventoryScope scope = InventoryScope.player("11111111-2222-3333-4444-555555555555");
        PushUpdate update = new PushUpdate(List.of(3, 4), 123L, scope);

        PushUpdate decoded = (PushUpdate) MessageCodec.decodeMessage(
                FrameType.PUSH_UPDATE, MessageCodec.encodeMessage(update));

        assertEquals(List.of(3, 4), decoded.getChangedSlots());
        assertEquals(123L, decoded.getTimestamp());
        assertEquals(scope, decoded.getScope());
    }

    @Test
    void serverPushUpdateCanEncodeProtocolV1Shape() throws Exception {
        PushUpdate update = new PushUpdate(List.of(3, 4), 123L, InventoryScope.server());
        byte[] payload = MessageCodec.encodeMessage(update, 1);

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            assertEquals(123L, in.readLong());
            assertEquals(2, in.readInt());
            assertEquals(3, in.readInt());
            assertEquals(4, in.readInt());
            assertEquals(0, in.available(), "v1 push update must not include scope");
        }

        PushUpdate decoded = (PushUpdate) MessageCodec.decodeMessage(FrameType.PUSH_UPDATE, payload);
        assertEquals(InventoryScope.server(), decoded.getScope());
    }

    @Test
    void playerPushUpdateCannotEncodeProtocolV1Shape() {
        PushUpdate update = new PushUpdate(List.of(3), 123L,
                InventoryScope.player("11111111-2222-3333-4444-555555555555"));

        assertThrows(RuntimeException.class, () -> MessageCodec.encodeMessage(update, 1));
    }

    @Test
    void invalidListSizeIsRejected() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            BinaryIO.writeString(out, "bad-list");
            out.writeInt(257);
        }

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> MessageCodec.decodeMessage(FrameType.QUERY_SLOTS, bytes.toByteArray()));
        assertTrue(error.getMessage().contains("Failed to decode QUERY_SLOTS"));
    }

    private NeutralItem sampleItem() {
        NeutralItem item = new NeutralItem("minecraft:stone", 1, "Stone", new byte[] {1, 2},
                false, "1.21.11");
        item.setVersion(7);
        return item;
    }
}
