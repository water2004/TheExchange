package org.edtp.theexchange.network.codec;

import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.AuthRequest;
import org.edtp.theexchange.network.protocol.messages.AuthResponse;
import org.edtp.theexchange.network.protocol.messages.PushUpdate;
import org.edtp.theexchange.network.protocol.messages.PutItemRequest;
import org.edtp.theexchange.network.protocol.messages.QuerySlotsRequest;
import org.edtp.theexchange.network.protocol.messages.SlotStateResponse;
import org.edtp.theexchange.network.protocol.messages.SlotVersionsResponse;
import org.edtp.theexchange.network.protocol.messages.SlotsStateResponse;
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
        QuerySlotsRequest request = new QuerySlotsRequest("req-1", List.of(0, 1),
                InventoryAccess.player("Steve", "secret"));

        QuerySlotsRequest decoded = (QuerySlotsRequest) MessageCodec.decodeMessage(
                FrameType.QUERY_SLOTS, MessageCodec.encodeMessage(request));

        assertEquals("req-1", decoded.getRequestId());
        assertEquals(List.of(0, 1), decoded.getSlots());
        assertTrue(decoded.getAccess().isPlayer());
        assertEquals("Steve", decoded.getAccess().ownerName());
        assertEquals("secret", decoded.getAccess().password());
        assertNull(decoded.getAccess().effectiveScope());
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
    void playerQueryRequestCannotEncodeProtocolV1Shape() {
        QuerySlotsRequest request = new QuerySlotsRequest("req-player", List.of(0),
                InventoryAccess.player("Steve", "secret"));

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
        PutItemRequest request = new PutItemRequest(0, sampleItem(), 1,
                "req-put", "uuid", "Steve", InventoryAccess.player("Steve", "secret"));

        assertThrows(RuntimeException.class, () -> MessageCodec.encodeMessage(request, 1));
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

    private NeutralItem sampleItem() {
        NeutralItem item = new NeutralItem("minecraft:stone", 1, "Stone", new byte[] {1, 2},
                false, "1.21.11");
        item.setVersion(7);
        return item;
    }
}
