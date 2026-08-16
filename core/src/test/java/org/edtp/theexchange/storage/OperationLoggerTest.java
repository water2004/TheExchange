package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.OperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationLoggerTest {
    @TempDir
    Path tempDir;

    @Test
    void queryIncludesEntriesAlreadyFlushedToDisk() {
        OperationLogger first = new OperationLogger(tempDir);
        first.log(InventoryScope.player("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                "request-1", OperationType.PUT, "player", "Viewer",
                "remote", "minecraft:stone", 3, true, null);
        first.shutdown();

        OperationLogger reopened = new OperationLogger(tempDir);
        try {
            List<OperationLogger.LogEntry> logs = reopened.queryLogs(0L);

            assertEquals(1, logs.size(),
                    "the export query must not forget records after the background writer flushes them");
            assertEquals("request-1", logs.getFirst().requestId());
            assertEquals("minecraft:stone", logs.getFirst().itemId());
        } finally {
            reopened.shutdown();
        }
    }

    @Test
    void transientWriteFailureKeepsEntriesForTheNextFlush() throws Exception {
        Path blockedDirectory = tempDir.resolve("blocked");
        Files.writeString(blockedDirectory, "not a directory");
        OperationLogger logger = new OperationLogger(blockedDirectory);
        Method flush = OperationLogger.class.getDeclaredMethod("flushSafely");
        flush.setAccessible(true);
        try {
            logger.log("request-retry", OperationType.TAKE, "player", "Viewer",
                    "remote", "minecraft:diamond", 1, false, "network");
            flush.invoke(logger);

            Files.delete(blockedDirectory);
            Files.createDirectory(blockedDirectory);
            flush.invoke(logger);

            Path activeLog = blockedDirectory.resolve("operations.log");
            assertTrue(Files.exists(activeLog), "the retained batch must be written on retry");
            assertTrue(Files.readString(activeLog).contains("request-retry"));
        } finally {
            logger.shutdown();
        }
    }
}
