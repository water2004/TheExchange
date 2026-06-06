package org.edtp.theexchange.network;

import org.edtp.theexchange.network.codec.FrameDecoder;
import org.edtp.theexchange.network.codec.FrameEncoder;
import org.edtp.theexchange.network.codec.MessageCodec;
import org.edtp.theexchange.network.protocol.Frame;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.CorrelatedMessage;
import org.edtp.theexchange.network.sequence.SequenceWindow;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Represents a single TLS-encrypted connection to a remote server.
 * Each connection has 1 read thread and 1 write thread.
 */
public class Connection {
    private static final ScheduledExecutorService TIMEOUT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "exchange-connection-timeouts");
                thread.setDaemon(true);
                return thread;
            });

    private final String remoteName;
    private final SSLSocket socket;
    private final InputStream in;
    private final OutputStream out;
    private final AtomicLong sendSequence = new AtomicLong(0);
    private final AtomicBoolean disconnected = new AtomicBoolean(false);
    private final SequenceWindow recvWindow = new SequenceWindow();
    private volatile boolean running;
    private volatile boolean authenticated;
    private volatile boolean inbound;
    private volatile String peerServerName;
    private volatile int peerProtocolVersion = 1;
    private volatile long lastRecvTime;
    private Thread readThread;
    private BiConsumer<FrameType, Object> messageHandler;
    private volatile BiConsumer<Connection, Boolean> disconnectHandler;

    private final ConcurrentHashMap<ResponseKey, CompletableFuture<Object>> pendingResponses = new ConcurrentHashMap<>();
    public Connection(String remoteName, SSLSocket socket) throws IOException {
        this.remoteName = remoteName;
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.lastRecvTime = System.currentTimeMillis();
    }

    public synchronized void start(BiConsumer<FrameType, Object> handler) {
        this.messageHandler = handler;
        if (this.running) {
            return;
        }
        this.running = true;
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

    public int getPeerProtocolVersion() {
        return peerProtocolVersion;
    }

    public void setPeerProtocolVersion(String version) {
        this.peerProtocolVersion = parseProtocolVersion(version);
    }

    public boolean supportsInventoryAccess() {
        return peerProtocolVersion >= 2;
    }

    public void setDisconnectHandler(BiConsumer<Connection, Boolean> handler) {
        this.disconnectHandler = handler;
    }

    /**
     * Send a frame and return the sequence number (for fire-and-forget).
     */
    public long send(FrameType type, Object message) {
        Frame frame = new Frame(type, sendSequence.incrementAndGet(),
                System.currentTimeMillis(), MessageCodec.encodeMessage(message, peerProtocolVersion));
        byte[] data = FrameEncoder.encode(frame);
        synchronized (out) {
            try {
                out.write(data);
                out.flush();
            } catch (IOException e) {
                handleDisconnect();
            }
        }
        return frame.getSequence();
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
        CompletableFuture<Object> previous = pendingResponses.putIfAbsent(key, responseFuture);
        if (previous != null) {
            result.completeExceptionally(new IOException("Duplicate request id: " + requestId));
            return result;
        }

        byte[] data;
        try {
            Frame frame = new Frame(requestType, sendSequence.incrementAndGet(),
                    System.currentTimeMillis(), MessageCodec.encodeMessage(request, peerProtocolVersion));
            data = FrameEncoder.encode(frame);
        } catch (RuntimeException e) {
            pendingResponses.remove(key, responseFuture);
            result.completeExceptionally(e);
            return result;
        }
        ScheduledFuture<?> timeout = TIMEOUT_EXECUTOR.schedule(() -> {
            if (pendingResponses.remove(key, responseFuture)) {
                responseFuture.complete(null);
            }
        }, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);

        responseFuture.whenComplete((response, error) -> {
            timeout.cancel(false);
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete((T) response);
            }
        });

        synchronized (out) {
            try {
                out.write(data);
                out.flush();
            } catch (IOException e) {
                if (pendingResponses.remove(key, responseFuture)) {
                    responseFuture.completeExceptionally(e);
                }
                handleDisconnect();
            }
        }
        return result;
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
        System.out.println("[Exchange|Conn] Read loop started for " + remoteName);

        while (running) {
            try {
                int read = in.read(buf);
                if (read < 0) {
                    System.out.println("[Exchange|Conn] EOF from " + remoteName);
                    break;
                }

                byte[] data = new byte[read];
                System.arraycopy(buf, 0, data, 0, read);

                Frame frame = decoder.feed(data);
                while (frame != null) {
                    lastRecvTime = System.currentTimeMillis();

                    if (!recvWindow.validate(frame.getSequence(), frame.getTimestamp())) {
                        System.out.println("[Exchange|Conn] Replay rejected seq="
                                + frame.getSequence() + " from " + remoteName);
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
                            System.out.println("[Exchange|Conn] Protocol decode error from " + remoteName
                                    + ": " + e.getMessage());
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
                System.out.println("[Exchange|Conn] Read error from " + remoteName + ": " + e.getMessage());
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
        running = false;
        for (CompletableFuture<Object> future : pendingResponses.values()) {
            future.completeExceptionally(new IOException("Connection closed"));
        }
        pendingResponses.clear();
        try { socket.close(); } catch (IOException ignored) {}
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

    private int parseProtocolVersion(String version) {
        if (version == null || version.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(version.trim()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private record ResponseKey(FrameType type, String requestId) {}
}
