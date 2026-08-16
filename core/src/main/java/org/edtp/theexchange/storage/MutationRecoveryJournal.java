package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.InventoryAccess;
import org.edtp.theexchange.model.NeutralItem;
import org.edtp.theexchange.network.codec.MessageCodec;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.MutationExecute;
import org.edtp.theexchange.network.protocol.messages.MutationResultMessage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Persistent recovery state for unresolved mutations only. */
public final class MutationRecoveryJournal {
    public enum Direction { OUTBOUND, INBOUND }

    public record Entry(Direction direction, String peerId, String state,
                        MutationExecute request, MutationResultMessage result) {}

    private final DatabaseManager db;

    public MutationRecoveryJournal(DatabaseManager db) {
        this.db = db;
    }

    public void upsert(Direction direction, String peerId, String state,
                       MutationExecute request, MutationResultMessage result) {
        MutationExecute persistentRequest = withoutToken(request);
        MutationResultMessage persistentResult = withoutTransientLimits(result);
        String sql = "INSERT INTO mutation_recovery " +
                "(direction, peer_id, transaction_id, state, request_blob, result_blob, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(direction, peer_id, transaction_id) DO UPDATE SET " +
                "state=excluded.state, request_blob=excluded.request_blob, " +
                "result_blob=excluded.result_blob, updated_at=excluded.updated_at";
        db.lock();
        try (PreparedStatement statement = db.getConnection().prepareStatement(sql)) {
            statement.setString(1, direction.name());
            statement.setString(2, peerId);
            statement.setString(3, persistentRequest.getTransactionId());
            statement.setString(4, state);
            statement.setBytes(5, MessageCodec.encodeMessage(persistentRequest));
            statement.setBytes(6, persistentResult != null
                    ? MessageCodec.encodeMessage(persistentResult) : null);
            statement.setLong(7, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new RuntimeException("Failed to save mutation recovery state", error);
        } finally {
            db.unlock();
        }
    }

    public void delete(Direction direction, String peerId, String transactionId) {
        String sql = "DELETE FROM mutation_recovery WHERE direction=? AND peer_id=? AND transaction_id=?";
        db.lock();
        try (PreparedStatement statement = db.getConnection().prepareStatement(sql)) {
            statement.setString(1, direction.name());
            statement.setString(2, peerId);
            statement.setString(3, transactionId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new RuntimeException("Failed to delete mutation recovery state", error);
        } finally {
            db.unlock();
        }
    }

    public List<Entry> loadAll() {
        List<Entry> entries = new ArrayList<>();
        String sql = "SELECT direction, peer_id, state, request_blob, result_blob FROM mutation_recovery";
        db.lock();
        try (PreparedStatement statement = db.getConnection().prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                MutationExecute request = (MutationExecute) MessageCodec.decodeMessage(
                        FrameType.MUTATION_EXECUTE, resultSet.getBytes("request_blob"));
                byte[] resultBlob = resultSet.getBytes("result_blob");
                MutationResultMessage result = resultBlob != null
                        ? (MutationResultMessage) MessageCodec.decodeMessage(
                                FrameType.MUTATION_RESULT, resultBlob)
                        : null;
                entries.add(new Entry(Direction.valueOf(resultSet.getString("direction")),
                        resultSet.getString("peer_id"), resultSet.getString("state"), request, result));
            }
            return entries;
        } catch (SQLException error) {
            throw new RuntimeException("Failed to load mutation recovery state", error);
        } finally {
            db.unlock();
        }
    }

    private MutationExecute withoutToken(MutationExecute request) {
        InventoryAccess access = request.getAccess();
        InventoryAccess persistentAccess = access.isPlayer()
                ? InventoryAccess.playerSession(access.ownerName(), "", access.requesterUuid(),
                        access.requesterName(), access.resolvedScope(), 0, access.sessionTtlMillis())
                : InventoryAccess.server();
        return new MutationExecute(request.getTransactionId(), request.getIntentHash(), request.getKind(),
                request.getSlot(), withoutTransientLimit(request.getOfferedItem()), request.getExpectedItemId(),
                request.getExpectedVersion(), request.getCount(), request.isBoundedMerge(),
                request.getPlayerUuid(), request.getPlayerName(), persistentAccess);
    }

    private MutationResultMessage withoutTransientLimits(MutationResultMessage result) {
        if (result == null) return null;
        return new MutationResultMessage(result.getTransactionId(), result.getIntentHash(),
                result.getResultHash(), result.getKind(), result.isSuccess(), result.getSlot(),
                withoutTransientLimit(result.getCurrentItem()),
                withoutTransientLimit(result.getTransferredItem()), result.getFailReason(),
                result.getNewTimestamp(), result.getNewVersion(), result.getScope());
    }

    private NeutralItem withoutTransientLimit(NeutralItem item) {
        if (item == null) return null;
        NeutralItem copy = item.copy();
        copy.setMaxStackSize(0);
        return copy;
    }
}
