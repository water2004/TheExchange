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
import java.util.concurrent.ConcurrentHashMap;
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
    private final ConcurrentHashMap<String, Frame> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicLong pendingId = new AtomicLong(0);

    private volatile boolean running;
    private volatile long lastRecvTime;
    private Thread readThread;
    private BiConsumer<FrameType, Object> messageHandler;
    private volatile BiConsumer<Connection, Boolean> disconnectHandler;

    // Result tracking for request-response pattern
    private final ConcurrentHashMap<String, Object> responseResults = new ConcurrentHashMap<>();

    public Connection(String remoteName, SSLSocket socket) throws IOException {
        this.remoteName = remoteName;
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.lastRecvTime = System.currentTimeMillis();
    }

    public void start(BiConsumer<FrameType, Object> handler) {
        this.messageHandler = handler;
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

    /**
     * Send a request and wait for the response (synchronous request-response).
     */
    @SuppressWarnings("unchecked")
    public <T> T sendAndWait(FrameType requestType, Object request,
                              FrameType responseType, long timeoutMs) {
        String responseKey = responseType.name() + "_" + pendingId.incrementAndGet();

        Frame frame = new Frame(requestType, sendSequence.incrementAndGet(),
                System.currentTimeMillis(), MessageCodec.encodeMessage(request));
        byte[] data = FrameEncoder.encode(frame);
        synchronized (out) {
            try {
                out.write(data);
                out.flush();
            } catch (IOException e) {
                handleDisconnect();
                return null;
            }
        }

        // Wait for response
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            Object result = responseResults.remove(responseKey);
            if (result != null) {
                return (T) result;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null; // Timeout
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
                        Object message = MessageCodec.decodeMessage(frame.getType(), frame.getPayload());
                        if (messageHandler != null) {
                            dispatchMessage(frame.getType(), message);
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
}
