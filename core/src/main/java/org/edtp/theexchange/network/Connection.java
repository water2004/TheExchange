package org.edtp.theexchange.network;

import org.edtp.theexchange.network.codec.FrameDecoder;
import org.edtp.theexchange.network.codec.FrameEncoder;
import org.edtp.theexchange.network.codec.MessageCodec;
import org.edtp.theexchange.network.protocol.Frame;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.CorrelatedMessage;
import org.edtp.theexchange.network.sequence.SequenceWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Represents a single TLS-encrypted connection to a remote server.
 * Each connection has 1 read thread and 1 write thread.
 */
public class Connection {
    private static final Logger LOGGER = LoggerFactory.getLogger(Connection.class);
    private static final ScheduledThreadPoolExecutor TIMEOUT_EXECUTOR = createTimeoutExecutor();

    private final String remoteName;
    private final SSLSocket socket;
    private final InputStream in;
    private final OutputStream out;
    private final ExecutorService writeExecutor;
    private final AtomicLong sendSequence = new AtomicLong(0);
    private final AtomicBoolean disconnected = new AtomicBoolean(false);
    private final Object responseLifecycle = new Object();
    private final Object sendLifecycle = new Object();
    private final SequenceWindow recvWindow = new SequenceWindow();
    private volatile boolean running;
    private volatile boolean authenticated;
    private volatile boolean inbound;
    private volatile String peerServerName;
    private volatile long lastRecvTime;
    private Thread readThread;
    private BiConsumer<FrameType, Object> messageHandler;
    private volatile BiConsumer<Connection, Boolean> disconnectHandler;

    private final ConcurrentHashMap<ResponseKey, CompletableFuture<Object>> pendingResponses = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<Void>> pendingWrites = ConcurrentHashMap.newKeySet();
    public Connection(String remoteName, SSLSocket socket) throws IOException {
        this.remoteName = remoteName;
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.writeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "exchange-conn-writer-" + remoteName);
            thread.setDaemon(true);
            return thread;
        });
        this.lastRecvTime = System.currentTimeMillis();
    }

    public synchronized void start(BiConsumer<FrameType, Object> handler) {
        this.messageHandler = handler;
        synchronized (responseLifecycle) {
            if (this.running || disconnected.get()) {
                return;
            }
            this.running = true;
        }
        this.readThread = new Thread(this::readLoop, "exchange-conn-" + remoteName);
        this.readThread.setDaemon(true);
        this.readThread.start();
    }

    public String getRemoteName() {
        return remoteName;
    }

    public long getLastRecvTime() {
        return lastRecvTime;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public boolean isInbound() {
        return inbound;
    }

    public void setInbound(boolean inbound) {
        this.inbound = inbound;
    }

    public String getPeerServerName() {
        return peerServerName;
    }

    public void setPeerServerName(String peerServerName) {
        this.peerServerName = peerServerName;
    }

    public void setDisconnectHandler(BiConsumer<Connection, Boolean> handler) {
        this.disconnectHandler = handler;
    }

    /**
     * Send a frame and return the sequence number (for fire-and-forget).
     */
    public long send(FrameType type, Object message) {
        final Frame frame;
        CompletableFuture<Void> writeCompletion = new CompletableFuture<>();
        synchronized (sendLifecycle) {
            frame = new Frame(type, sendSequence.incrementAndGet(),
                    System.currentTimeMillis(), MessageCodec.encodeMessage(message));
            byte[] data = FrameEncoder.encode(frame);
            pendingWrites.add(writeCompletion);
            try {
                writeExecutor.execute(() -> {
                    try {
                        writeFrame(data);
                    } catch (IOException e) {
                        handleDisconnect();
                    } finally {
                        pendingWrites.remove(writeCompletion);
                        writeCompletion.complete(null);
                    }
                });
            } catch (RejectedExecutionException e) {
                pendingWrites.remove(writeCompletion);
                writeCompletion.completeExceptionally(new IOException("Connection closed", e));
                handleDisconnect();
                return frame.getSequence();
            }
        }
        try {
            writeCompletion.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleDisconnect();
        } catch (ExecutionException e) {
            handleDisconnect();
        }
        return frame.getSequence();
    }

    /** Queue a frame without blocking the caller; completion means the bytes were flushed to TCP. */
    public CompletableFuture<Void> sendOneWay(FrameType type, Object message) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        if (!running || disconnected.get()) {
            completion.completeExceptionally(new IOException("Connection closed"));
            return completion;
        }
        try {
            synchronized (sendLifecycle) {
                if (!running || disconnected.get()) {
                    completion.completeExceptionally(new IOException("Connection closed"));
                    return completion;
                }
                Frame frame = new Frame(type, sendSequence.incrementAndGet(),
                        System.currentTimeMillis(), MessageCodec.encodeMessage(message));
                byte[] data = FrameEncoder.encode(frame);
                pendingWrites.add(completion);
                writeExecutor.execute(() -> {
                    try {
                        writeFrame(data);
                        completion.complete(null);
                    } catch (IOException error) {
                        completion.completeExceptionally(error);
                        handleDisconnect();
                    } finally {
                        pendingWrites.remove(completion);
                    }
                });
            }
        } catch (RejectedExecutionException error) {
            pendingWrites.remove(completion);
            completion.completeExceptionally(new IOException("Connection closed", error));
            handleDisconnect();
        } catch (RuntimeException error) {
            pendingWrites.remove(completion);
            completion.completeExceptionally(error);
        }
        return completion;
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> sendAsync(FrameType requestType, Object request,
                                              FrameType responseType, long timeoutMs) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (!running || disconnected.get()) {
            result.completeExceptionally(new IOException("Connection closed"));
            return result;
        }

        String requestId = ensureRequestId(request);
        ResponseKey key = new ResponseKey(responseType, requestId);
        CompletableFuture<Object> responseFuture = new CompletableFuture<>();
        AtomicBoolean writeFinished = new AtomicBoolean(false);
        CompletableFuture<Object> previous;
        synchronized (responseLifecycle) {
            if (!running || disconnected.get()) {
                result.completeExceptionally(new IOException("Connection closed"));
                return result;
            }
            previous = pendingResponses.putIfAbsent(key, responseFuture);
        }
        if (previous != null) {
            result.completeExceptionally(new IOException("Duplicate request id: " + requestId));
            return result;
        }

        ScheduledFuture<?> timeout;
        try {
            timeout = TIMEOUT_EXECUTOR.schedule(() -> {
                if (pendingResponses.remove(key, responseFuture)) {
                    responseFuture.completeExceptionally(new TimeoutException(
                            "Request timed out after " + timeoutMs + "ms: " + requestType));
                    if (!writeFinished.get()) {
                        handleDisconnect();
                    }
                }
            }, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            pendingResponses.remove(key, responseFuture);
            result.completeExceptionally(e);
            return result;
        }

        responseFuture.whenComplete((response, error) -> {
            timeout.cancel(false);
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete((T) response);
            }
        });

        try {
            synchronized (sendLifecycle) {
                if (responseFuture.isDone() || disconnected.get()) {
                    return result;
                }
                Frame frame = new Frame(requestType, sendSequence.incrementAndGet(),
                        System.currentTimeMillis(), MessageCodec.encodeMessage(request));
                byte[] data = FrameEncoder.encode(frame);
                writeExecutor.execute(() -> {
                    if (responseFuture.isDone() || disconnected.get()) {
                        return;
                    }
                    try {
                        writeFrame(data);
                    } catch (IOException e) {
                        if (pendingResponses.remove(key, responseFuture)) {
                            responseFuture.completeExceptionally(e);
                        }
                        handleDisconnect();
                    } finally {
                        writeFinished.set(true);
                    }
                });
            }
        } catch (RejectedExecutionException e) {
            if (pendingResponses.remove(key, responseFuture)) {
                responseFuture.completeExceptionally(new IOException("Connection closed", e));
            }
            handleDisconnect();
        } catch (RuntimeException e) {
            if (pendingResponses.remove(key, responseFuture)) {
                responseFuture.completeExceptionally(e);
            }
        }
        return result;
    }

    private static ScheduledThreadPoolExecutor createTimeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "exchange-connection-timeouts");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private void writeFrame(byte[] data) throws IOException {
        synchronized (out) {
            out.write(data);
            out.flush();
        }
    }

    /**
     * Feed a received response to any pending sendAsync future.
     */
    public void onResponse(FrameType type, Object message) {
        String requestId = message instanceof CorrelatedMessage correlated ? correlated.getRequestId() : null;
        if (requestId == null) {
            return;
        }
        CompletableFuture<Object> future = pendingResponses.remove(new ResponseKey(type, requestId));
        if (future != null) {
            future.complete(message);
        }
    }

    public void sendResponse(FrameType responseType, Object response) {
        send(responseType, response);
    }

    private void readLoop() {
        FrameDecoder decoder = new FrameDecoder();
        byte[] buf = new byte[8192];
        LOGGER.trace("Read loop started for {}", remoteName);

        while (running) {
            try {
                int read = in.read(buf);
                if (read < 0) {
                    LOGGER.debug("Connection reached EOF from {}", remoteName);
                    break;
                }

                byte[] data = new byte[read];
                System.arraycopy(buf, 0, data, 0, read);

                Frame frame = decoder.feed(data);
                while (frame != null) {
                    lastRecvTime = System.currentTimeMillis();

                    if (!recvWindow.validate(frame.getSequence(), frame.getTimestamp())) {
                        LOGGER.warn("Rejected replayed frame sequence {} from {}",
                                frame.getSequence(), remoteName);
                        frame = decoder.feed(new byte[0]);
                        continue;
                    }

                    if (frame.hasPayload() && frame.getType() != FrameType.HEARTBEAT) {
                        try {
                            Object message = MessageCodec.decodeMessage(frame.getType(), frame.getPayload());
                            if (messageHandler != null) {
                                dispatchMessage(frame.getType(), message);
                            }
                        } catch (RuntimeException e) {
                            LOGGER.warn("Protocol decode error from {}: {}", remoteName, e.getMessage());
                            running = false;
                            break;
                        }
                    } else if (frame.getType() == FrameType.HEARTBEAT) {
                        if (messageHandler != null) {
                            messageHandler.accept(frame.getType(), null);
                        }
                    }

                    frame = decoder.feed(new byte[0]);
                }
            } catch (IOException e) {
                if (running) {
                    LOGGER.debug("Connection read error from {}: {}", remoteName, e.getMessage());
                }
                break;
            }
        }

        handleDisconnect();
    }

    private void dispatchMessage(FrameType type, Object message) {
        if (messageHandler != null) {
            messageHandler.accept(type, message);
        }
    }

    private void handleDisconnect() {
        if (!disconnected.compareAndSet(false, true)) {
            return;
        }
        List<CompletableFuture<Object>> abandoned = new ArrayList<>();
        synchronized (responseLifecycle) {
            running = false;
            abandoned.addAll(pendingResponses.values());
            pendingResponses.clear();
        }
        try { socket.close(); } catch (IOException ignored) {}
        writeExecutor.shutdownNow();
        IOException closed = new IOException("Connection closed");
        for (CompletableFuture<Void> write : pendingWrites) {
            write.completeExceptionally(closed);
        }
        pendingWrites.clear();
        for (CompletableFuture<Object> future : abandoned) {
            future.completeExceptionally(closed);
        }
        if (disconnectHandler != null) {
            disconnectHandler.accept(this, false);
        }
    }

    public void close() {
        if (readThread != null) {
            readThread.interrupt();
        }
        handleDisconnect();
    }

    private String ensureRequestId(Object message) {
        if (message instanceof CorrelatedMessage correlated) {
            String requestId = correlated.getRequestId();
            if (requestId == null || requestId.isBlank()) {
                requestId = UUID.randomUUID().toString();
                correlated.setRequestId(requestId);
            }
            return requestId;
        }
        return UUID.randomUUID().toString();
    }

    private record ResponseKey(FrameType type, String requestId) {}
}
