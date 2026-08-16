package org.edtp.theexchange.network.codec;

import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.MutationHashes;
import org.edtp.theexchange.network.protocol.messages.*;
import org.edtp.theexchange.util.BinaryIO;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageCodecPlayerInventoryTest {

    @Test
    void authResponseRoundTripsRequiredProtocolVersion() {
        AuthResponse response = new AuthResponse(true, "OK", "remote", "1.21.11", 123L);
        AuthResponse decoded = (AuthResponse) MessageCodec.decodeMessage(
                FrameType.AUTH_RESPONSE, MessageCodec.encodeMessage(response));
        assertEquals(AuthRequest.CURRENT_PROTOCOL_VERSION, decoded.getVersion());
    }

    @Test
    void authResponseWithoutV2VersionIsRejected() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeBoolean(true);
            BinaryIO.writeString(out, "OK");
            BinaryIO.writeString(out, "legacy");
            BinaryIO.writeString(out, "1.21.11");
            out.writeLong(123L);
        }
        assertThrows(RuntimeException.class, () -> MessageCodec.decodeMessage(
                FrameType.AUTH_RESPONSE, bytes.toByteArray()));
    }

    @Test
    void requestWithoutV2AccessFieldsIsRejected() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            BinaryIO.writeString(out, "old-request");
            out.writeInt(1);
            out.writeInt(0);
        }
        assertThrows(RuntimeException.class, () -> MessageCodec.decodeMessage(
                FrameType.QUERY_SLOTS, bytes.toByteArray()));
    }

    @Test
    void queryRequestRoundTripsPlayerAccess() {
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        QuerySlotsRequest request = new QuerySlotsRequest("req-1", List.of(0, 1),
                InventoryAccess.playerSession("Steve", "token-123", "viewer-uuid", "Viewer",
                        scope, 12345L));
        QuerySlotsRequest decoded = (QuerySlotsRequest) MessageCodec.decodeMessage(
                FrameType.QUERY_SLOTS, MessageCodec.encodeMessage(request));
        assertEquals(List.of(0, 1), decoded.getSlots());
        assertTrue(decoded.getAccess().isPlayer());
        assertEquals("Steve", decoded.getAccess().ownerName());
        assertEquals("token-123", decoded.getAccess().token());
        assertEquals("viewer-uuid", decoded.getAccess().requesterUuid());
        assertEquals(scope, decoded.getAccess().effectiveScope());
    }

    @Test
    void playerAccessHandshakeRoundTripsPasswordAndIssuedToken() {
        PlayerInventoryAccessRequest request = new PlayerInventoryAccessRequest(
                "access-req", "Steve", "secret", "viewer-uuid", "Viewer");
        PlayerInventoryAccessRequest decodedRequest = (PlayerInventoryAccessRequest) MessageCodec.decodeMessage(
                FrameType.PLAYER_INVENTORY_ACCESS, MessageCodec.encodeMessage(request));
        assertEquals("secret", decodedRequest.getPassword());

        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        PlayerInventoryAccessResponse response = PlayerInventoryAccessResponse.success(
                "access-req", "Steve", "token-123", scope, 12345L, 300_000L);
        PlayerInventoryAccessResponse decodedResponse = (PlayerInventoryAccessResponse) MessageCodec.decodeMessage(
                FrameType.PLAYER_INVENTORY_ACCESS_RESPONSE, MessageCodec.encodeMessage(response));
        assertEquals("token-123", decodedResponse.getToken());
        assertEquals(scope, decodedResponse.getScope());
    }

    @Test
    void queryResponseRoundTripsScopeFailureAndRemoteLimit() {
        InventoryScope scope = InventoryScope.player("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE");
        SlotStateResponse response = new SlotStateResponse("req-2", 3, sampleItem(), 7, scope);
        response.setSuccess(false);
        response.setFailReason("VERSION_MISMATCH");
        SlotStateResponse decoded = (SlotStateResponse) MessageCodec.decodeMessage(
                FrameType.SLOT_STATE_RESPONSE, MessageCodec.encodeMessage(response));
        assertFalse(decoded.isSuccess());
        assertEquals("VERSION_MISMATCH", decoded.getFailReason());
        assertEquals(scope, decoded.getScope());
        assertEquals(16, decoded.getItem().getMaxStackSize());
    }

    @Test
    void playerMutationMessagesRoundTripInProtocolV2() {
        InventoryScope scope = InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        InventoryAccess access = InventoryAccess.playerSession(
                "Steve", "token-123", "actor", "Viewer", scope, 12345L);

        MutationExecute execute = new MutationExecute("put-v2", null, MutationKind.PUT,
                0, sampleItem(), null, 1, 1, false, "actor", "Steve", access);
        execute.setIntentHash(MutationHashes.intent(execute));
        MutationExecute put = (MutationExecute) MessageCodec.decodeMessage(
                FrameType.MUTATION_EXECUTE, MessageCodec.encodeMessage(execute));
        assertTrue(put.getAccess().isPlayer());
        assertEquals(16, put.getOfferedItem().getMaxStackSize());
        assertTrue(MutationHashes.validIntent(put));

        MutationResultMessage takeMessage = new MutationResultMessage(
                "take-v2", "intent", null, MutationKind.TAKE, true, 0,
                null, sampleItem(), null, 2L, 3, scope);
        takeMessage.setResultHash(MutationHashes.result(takeMessage));
        MutationResultMessage take = (MutationResultMessage) MessageCodec.decodeMessage(
                FrameType.MUTATION_RESULT, MessageCodec.encodeMessage(takeMessage));
        assertEquals(scope, take.getScope());
        assertEquals(16, take.getTransferredItem().getMaxStackSize());
        assertTrue(MutationHashes.validResult(take));

        TransactionStatus status = new TransactionStatus("take-v2", "intent",
                TransactionStatus.State.DECIDED, takeMessage);
        TransactionStatus decodedStatus = (TransactionStatus) MessageCodec.decodeMessage(
                FrameType.TRANSACTION_STATUS, MessageCodec.encodeMessage(status));
        assertEquals(TransactionStatus.State.DECIDED, decodedStatus.getState());
        assertEquals("take-v2", decodedStatus.getResult().getTransactionId());

        TransactionQuery query = (TransactionQuery) MessageCodec.decodeMessage(
                FrameType.TRANSACTION_QUERY,
                MessageCodec.encodeMessage(new TransactionQuery("take-v2", "intent")));
        assertEquals("intent", query.intentHash());

        TransactionStatus conflict = (TransactionStatus) MessageCodec.decodeMessage(
                FrameType.TRANSACTION_STATUS,
                MessageCodec.encodeMessage(new TransactionStatus("take-v2", "other-intent",
                        TransactionStatus.State.CONFLICT, null)));
        assertEquals(TransactionStatus.State.CONFLICT, conflict.getState());
        assertNull(conflict.getResult());

        TransactionSettled settled = (TransactionSettled) MessageCodec.decodeMessage(
                FrameType.TRANSACTION_SETTLED,
                MessageCodec.encodeMessage(new TransactionSettled("take-v2", "result-hash")));
        assertEquals("result-hash", settled.resultHash());

        TransactionClosed closed = (TransactionClosed) MessageCodec.decodeMessage(
                FrameType.TRANSACTION_CLOSED,
                MessageCodec.encodeMessage(new TransactionClosed("take-v2", "result-hash")));
        assertEquals("take-v2", closed.transactionId());
        assertEquals("result-hash", closed.resultHash());
    }

    @Test
    void pushUpdateRoundTripsResolvedScope() {
        InventoryScope scope = InventoryScope.player("11111111-2222-3333-4444-555555555555");
        PushUpdate decoded = (PushUpdate) MessageCodec.decodeMessage(
                FrameType.PUSH_UPDATE,
                MessageCodec.encodeMessage(new PushUpdate(List.of(3, 4), 123L, scope)));
        assertEquals(List.of(3, 4), decoded.getChangedSlots());
        assertEquals(scope, decoded.getScope());
    }

    @Test
    void invalidListSizeIsRejected() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            BinaryIO.writeString(out, "bad-list");
            out.writeInt(257);
        }
        assertThrows(RuntimeException.class,
                () -> MessageCodec.decodeMessage(FrameType.QUERY_SLOTS, bytes.toByteArray()));
    }

    private NeutralItem sampleItem() {
        NeutralItem item = new NeutralItem("minecraft:stone", 1, "Stone", new byte[] {1, 2},
                false, "1.21.11");
        item.setVersion(7);
        item.setMaxStackSize(16);
        return item;
    }
}
