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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Represents a single TLS-encrypted connection to a remote server.
 * Each connection has 1 read thread and 1 write thread.
 */
public class Connection {

    private final String remoteName;
    private final SSLSocket socket;
    private final InputStream in;
    private final OutputStream out;
    private final AtomicLong sendSequence = new AtomicLong(0);
    private final SequenceWindow recvWindow = new SequenceWindow();
    private volatile boolean running;
    private volatile boolean authenticated;
    private volatile String peerServerName;
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
        Frame frame = new Frame(type, sendSequence.incrementAndGet(),
                System.currentTimeMillis(), MessageCodec.encodeMessage(message));
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
    private <T> T sendAndWait(FrameType requestType, Object request,
                              FrameType responseType, long timeoutMs, String requestId) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        ResponseKey key = new ResponseKey(responseType, requestId);
        pendingResponses.put(key, future);

        Frame frame = new Frame(requestType, sendSequence.incrementAndGet(),
                System.currentTimeMillis(), MessageCodec.encodeMessage(request));
        byte[] data = FrameEncoder.encode(frame);
        synchronized (out) {
            try {
                out.write(data);
                out.flush();
            } catch (IOException e) {
                pendingResponses.remove(key, future);
                handleDisconnect();
                return null;
            }
        }

        try {
            return (T) future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            pendingResponses.remove(key, future);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> sendAsync(FrameType requestType, Object request,
                                              FrameType responseType, long timeoutMs) {
        CompletableFuture<T> future = new CompletableFuture<>();
        String requestId = ensureRequestId(request);
        Thread thread = new Thread(() -> {
            T response = sendAndWait(requestType, request, responseType, timeoutMs, requestId);
            future.complete(response);
        }, "exchange-req-" + remoteName + "-" + requestId);
        thread.setDaemon(true);
        thread.start();
        return future;
    }

    /**
     * Feed a received response to any pending sendAndWait future.
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
        running = false;
        for (CompletableFuture<Object> future : pendingResponses.values()) {
            future.complete(null);
        }
        pendingResponses.clear();
        try { socket.close(); } catch (IOException ignored) {}
        if (disconnectHandler != null) {
            disconnectHandler.accept(this, false);
        }
    }

    public void close() {
        running = false;
        if (readThread != null) {
            readThread.interrupt();
        }
        try { socket.close(); } catch (IOException ignored) {}
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
