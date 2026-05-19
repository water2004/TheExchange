package org.edtp.theexchange.network;

import org.edtp.theexchange.network.codec.FrameDecoder;
import org.edtp.theexchange.network.codec.FrameEncoder;
import org.edtp.theexchange.network.codec.MessageCodec;
import org.edtp.theexchange.network.protocol.Frame;
import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.sequence.SequenceWindow;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
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

    private final Semaphore requestPermit = new Semaphore(1);
    private volatile FrameType pendingResponseType;
    private volatile CompletableFuture<Object> pendingFuture;
    private final ExecutorService requestExecutor;
    public Connection(String remoteName, SSLSocket socket) throws IOException {
        this.remoteName = remoteName;
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.lastRecvTime = System.currentTimeMillis();
        this.requestExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "exchange-req-" + remoteName);
            thread.setDaemon(true);
            return thread;
        });
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
    public <T> T sendAndWait(FrameType requestType, Object request,
                              FrameType responseType, long timeoutMs) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        requestPermit.acquireUninterruptibly();
        pendingResponseType = responseType;
        pendingFuture = future;

        Frame frame = new Frame(requestType, sendSequence.incrementAndGet(),
                System.currentTimeMillis(), MessageCodec.encodeMessage(request));
        byte[] data = FrameEncoder.encode(frame);
        synchronized (out) {
            try {
                out.write(data);
                out.flush();
            } catch (IOException e) {
                if (clearPending(future)) {
                    requestPermit.release();
                }
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
            if (clearPending(future)) {
                requestPermit.release();
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> sendAsync(FrameType requestType, Object request,
                                              FrameType responseType, long timeoutMs) {
        CompletableFuture<T> future = new CompletableFuture<>();
        requestExecutor.execute(() -> {
            try {
                T response = sendAndWait(requestType, request, responseType, timeoutMs);
                future.complete(response);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Feed a received response to any pending sendAndWait future.
     */
    public void onResponse(FrameType type, Object message) {
        CompletableFuture<Object> future = pendingFuture;
        if (future != null && pendingResponseType == type) {
            if (clearPending(future)) {
                requestPermit.release();
                future.complete(message);
            }
        }
    }

    private synchronized boolean clearPending(CompletableFuture<Object> future) {
        if (pendingFuture == future) {
            pendingFuture = null;
            pendingResponseType = null;
            return true;
        }
        return false;
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
        CompletableFuture<Object> future = pendingFuture;
        if (future != null && clearPending(future)) {
            requestPermit.release();
            future.complete(null);
        }
        try { socket.close(); } catch (IOException ignored) {}
        if (disconnectHandler != null) {
            disconnectHandler.accept(this, false);
        }
    }

    public void close() {
        running = false;
        requestExecutor.shutdownNow();
        if (readThread != null) {
            readThread.interrupt();
        }
        try { socket.close(); } catch (IOException ignored) {}
    }
}
