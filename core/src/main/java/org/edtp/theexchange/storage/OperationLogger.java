package org.edtp.theexchange.storage;

import org.edtp.theexchange.model.InventoryScope;
import org.edtp.theexchange.model.OperationType;

import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lock-free operation audit buffer.
 *
 * Hot exchange paths only append to an in-memory queue. A single background
 * writer drains the queue to rotating text files for persistence.
 */
public class OperationLogger {
    private static final int FLUSH_THRESHOLD = 200;
    private static final long FLUSH_INTERVAL_SECONDS = 5;
    private static final long MAX_LOG_FILE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_ARCHIVES = 10;
    private static final String ACTIVE_LOG_NAME = "operations.log";
    private static final DateTimeFormatter ARCHIVE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Path logDir;
    private final Path activeLog;
    private final ConcurrentLinkedQueue<LogEntry> entries = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedCount = new AtomicInteger();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean flushQueued = new AtomicBoolean();
    private final ReentrantLock ioLock = new ReentrantLock();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "exchange-operation-log-flusher");
        thread.setDaemon(true);
        return thread;
    });

    public OperationLogger(Path logDir) {
        this.logDir = logDir;
        this.activeLog = logDir.resolve(ACTIVE_LOG_NAME);
        flusher.scheduleWithFixedDelay(this::flushSafely,
                FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public boolean log(String requestId, OperationType opType, String playerUuid, String playerName,
                       String serverName, String itemId, int quantity, boolean success, String failReason) {
        return log(InventoryScope.server(), requestId, opType, playerUuid, playerName,
                serverName, itemId, quantity, success, failReason);
    }

    public boolean log(InventoryScope scope, String requestId, OperationType opType,
                       String playerUuid, String playerName, String serverName,
                       String itemId, int quantity, boolean success, String failReason) {
        LogEntry entry = new LogEntry(
                sequence.incrementAndGet(),
                System.currentTimeMillis(),
                opType,
                scope,
                playerUuid,
                playerName,
                serverName,
                itemId,
                quantity,
                success,
                failReason,
                requestId);
        entries.offer(entry);
        if (queuedCount.incrementAndGet() >= FLUSH_THRESHOLD) {
            flushAsync();
        }
        return true;
    }

    public List<LogEntry> queryLogs(long sinceTimestamp) {
        List<LogEntry> results = new ArrayList<>();
        ioLock.lock();
        try {
            readPersistedEntries(sinceTimestamp, results);
            for (LogEntry entry : entries) {
                if (entry.timestamp() >= sinceTimestamp) {
                    results.add(entry);
                }
            }
        } finally {
            ioLock.unlock();
        }
        results.sort(Comparator.comparingLong(LogEntry::timestamp).reversed());
        return results;
    }

    public int cleanupOldLogs(int retentionDays) {
        long cutoff = System.currentTimeMillis() - (long) retentionDays * 24 * 3600 * 1000;
        ioLock.lock();
        try {
            int removed = 0;
            for (LogEntry entry : entries) {
                if (entry.timestamp() < cutoff && entries.remove(entry)) {
                    removed++;
                }
            }
            if (removed > 0) {
                queuedCount.addAndGet(-removed);
            }
            return removed + deleteOldLogFiles(cutoff);
        } finally {
            ioLock.unlock();
        }
    }

    public void shutdown() {
        flushSafely();
        flusher.shutdownNow();
    }

    private void flushAsync() {
        if (!flushQueued.compareAndSet(false, true)) {
            return;
        }
        flusher.execute(this::flushSafely);
    }

    private void flushSafely() {
        flushQueued.set(false);
        try {
            flush();
        } catch (Exception ignored) {
            // Audit persistence must never block or fail exchange operations.
        }
    }

    private void flush() throws IOException {
        ioLock.lock();
        List<LogEntry> batch = List.of();
        try {
            batch = drainEntries();
            if (batch.isEmpty()) {
                return;
            }
            Files.createDirectories(logDir);
            rotateIfNeeded();
            try (BufferedWriter writer = Files.newBufferedWriter(activeLog, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (LogEntry entry : batch) {
                    writer.write(encode(entry));
                    writer.newLine();
                }
            }
        } catch (IOException | RuntimeException error) {
            restoreEntries(batch);
            throw error;
        } finally {
            ioLock.unlock();
        }
    }

    private List<LogEntry> drainEntries() {
        List<LogEntry> batch = new ArrayList<>();
        LogEntry entry;
        while ((entry = entries.poll()) != null) {
            queuedCount.decrementAndGet();
            batch.add(entry);
        }
        return batch;
    }

    private void restoreEntries(List<LogEntry> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (LogEntry entry : batch) {
            entries.offer(entry);
        }
        queuedCount.addAndGet(batch.size());
    }

    private void readPersistedEntries(long sinceTimestamp, List<LogEntry> results) {
        if (!Files.isDirectory(logDir)) {
            return;
        }
        try {
            List<Path> files = archiveFiles();
            if (Files.isRegularFile(activeLog)) {
                files.add(activeLog);
            }
            for (Path file : files) {
                try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        LogEntry entry = decode(line);
                        if (entry != null && entry.timestamp() >= sinceTimestamp) {
                            results.add(entry);
                        }
                    }
                }
            }
        } catch (IOException error) {
            throw new RuntimeException("Failed to query operation logs", error);
        }
    }

    private void rotateIfNeeded() throws IOException {
        if (!Files.exists(activeLog) || Files.size(activeLog) < MAX_LOG_FILE_BYTES) {
            return;
        }
        Path archive = logDir.resolve("operations-" + ARCHIVE_TIME.format(Instant.now()) + ".log");
        Files.move(activeLog, archive);
        pruneArchives();
    }

    private void pruneArchives() throws IOException {
        List<Path> archives = archiveFiles();
        archives.sort(Comparator.comparing(this::lastModifiedMillis).reversed());
        for (int i = MAX_ARCHIVES; i < archives.size(); i++) {
            Files.deleteIfExists(archives.get(i));
        }
    }

    private int deleteOldLogFiles(long cutoff) {
        int deleted = 0;
        try {
            for (Path file : archiveFiles()) {
                if (lastModifiedMillis(file) < cutoff && Files.deleteIfExists(file)) {
                    deleted++;
                }
            }
            if (Files.exists(activeLog) && lastModifiedMillis(activeLog) < cutoff
                    && Files.deleteIfExists(activeLog)) {
                deleted++;
            }
        } catch (IOException ignored) {
        }
        return deleted;
    }

    private List<Path> archiveFiles() throws IOException {
        List<Path> archives = new ArrayList<>();
        if (!Files.isDirectory(logDir)) {
            return archives;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir, "operations-*.log")) {
            for (Path path : stream) {
                archives.add(path);
            }
        }
        return archives;
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private String encode(LogEntry entry) {
        return entry.timestamp()
                + "\t" + entry.opType()
                + "\t" + safe(entry.scope().typeName())
                + "\t" + safe(entry.scope().getScopeId())
                + "\t" + safe(entry.playerUuid())
                + "\t" + safe(entry.playerName())
                + "\t" + safe(entry.serverName())
                + "\t" + safe(entry.itemId())
                + "\t" + entry.quantity()
                + "\t" + (entry.success() ? "SUCCESS" : "FAIL")
                + "\t" + safe(entry.failReason())
                + "\t" + safe(entry.requestId());
    }

    private LogEntry decode(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] fields = line.split("\t", -1);
        if (fields.length != 12) {
            return null;
        }
        try {
            long timestamp = Long.parseLong(fields[0]);
            OperationType operationType = OperationType.valueOf(fields[1]);
            InventoryScope scope = InventoryScope.of(unescape(fields[2]), unescape(fields[3]));
            int quantity = Integer.parseInt(fields[8]);
            boolean success = "SUCCESS".equals(fields[9]);
            return new LogEntry(0L, timestamp, operationType, scope,
                    unescape(fields[4]), unescape(fields[5]), unescape(fields[6]),
                    unescape(fields[7]), quantity, success, unescape(fields[10]),
                    unescape(fields[11]));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\' || index + 1 >= value.length()) {
                result.append(current);
                continue;
            }
            char escaped = value.charAt(++index);
            result.append(switch (escaped) {
                case 't' -> '\t';
                case 'r' -> '\r';
                case 'n' -> '\n';
                case '\\' -> '\\';
                default -> escaped;
            });
        }
        return result.toString();
    }

    public record LogEntry(long id, long timestamp, OperationType opType,
                           InventoryScope scope,
                           String playerUuid, String playerName,
                           String serverName, String itemId,
                           int quantity, boolean success,
                           String failReason, String requestId) {}
}
