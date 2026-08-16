package org.edtp.theexchange.network;

import org.edtp.theexchange.network.protocol.FrameType;
import org.edtp.theexchange.network.protocol.messages.QuerySlotVersionRequest;
import org.edtp.theexchange.network.protocol.messages.QuerySlotVersionResponse;
import org.junit.jupiter.api.Test;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionTimeoutTest {

    @Test
    void responseCompletesRequestBeforeDeadline() throws Exception {
        TestSocket socket = new TestSocket(new ByteArrayOutputStream());
        Connection connection = startedConnection(socket);
        QuerySlotVersionRequest request = new QuerySlotVersionRequest(4);

        CompletableFuture<QuerySlotVersionResponse> future = connection.sendAsync(
                FrameType.QUERY_SLOT_VERSION, request,
                FrameType.SLOT_VERSION_RESPONSE, 5_000);
        QuerySlotVersionResponse response = new QuerySlotVersionResponse(request.getRequestId(), 4, 7);
        connection.onResponse(FrameType.SLOT_VERSION_RESPONSE, response);

        assertSame(response, future.get(1, TimeUnit.SECONDS));
        connection.close();
    }

    @Test
    void timeoutCompletesExceptionallyEvenWhileSocketWriteIsBlocked() throws Exception {
        BlockingOutputStream output = new BlockingOutputStream();
        TestSocket socket = new TestSocket(output);
        Connection connection = startedConnection(socket);

        CompletableFuture<QuerySlotVersionResponse> future = assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> connection.sendAsync(
                        FrameType.QUERY_SLOT_VERSION, new QuerySlotVersionRequest(4),
                        FrameType.SLOT_VERSION_RESPONSE, 50));
        assertTrue(output.awaitWriteStarted(1, TimeUnit.SECONDS));

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(2, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        connection.close();
    }

    @Test
    void disconnectCompletesEveryRegisteredRequest() throws Exception {
        TestSocket socket = new TestSocket(new ByteArrayOutputStream());
        Connection connection = startedConnection(socket);
        CompletableFuture<QuerySlotVersionResponse> future = connection.sendAsync(
                FrameType.QUERY_SLOT_VERSION, new QuerySlotVersionRequest(4),
                FrameType.SLOT_VERSION_RESPONSE, 30_000);

        connection.close();

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, failure.getCause());
    }

    private static Connection startedConnection(TestSocket socket) throws IOException {
        Connection connection = new Connection("timeout-test", socket);
        connection.start((type, message) -> {});
        return connection;
    }

    private static final class BlockingOutputStream extends OutputStream {
        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void write(int value) throws IOException {
            blockUntilClosed();
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            blockUntilClosed();
        }

        boolean awaitWriteStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return writeStarted.await(timeout, unit);
        }

        @Override
        public void close() {
            closed.countDown();
        }

        private void blockUntilClosed() throws IOException {
            writeStarted.countDown();
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("write interrupted", e);
            }
            throw new IOException("socket closed");
        }
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            try {
                closed.await();
                return -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("read interrupted", e);
            }
        }

        @Override
        public int read(byte[] data, int offset, int length) throws IOException {
            return read();
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class TestSocket extends SSLSocket {
        private final BlockingInputStream input = new BlockingInputStream();
        private final OutputStream output;

        private TestSocket(OutputStream output) {
            this.output = output;
        }

        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public String[] getEnabledCipherSuites() { return new String[0]; }
        @Override public void setEnabledCipherSuites(String[] suites) {}
        @Override public String[] getSupportedProtocols() { return new String[0]; }
        @Override public String[] getEnabledProtocols() { return new String[0]; }
        @Override public void setEnabledProtocols(String[] protocols) {}
        @Override public SSLSession getSession() { return null; }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {}
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {}
        @Override public void startHandshake() {}
        @Override public void setUseClientMode(boolean mode) {}
        @Override public boolean getUseClientMode() { return false; }
        @Override public void setNeedClientAuth(boolean need) {}
        @Override public boolean getNeedClientAuth() { return false; }
        @Override public void setWantClientAuth(boolean want) {}
        @Override public boolean getWantClientAuth() { return false; }
        @Override public void setEnableSessionCreation(boolean flag) {}
        @Override public boolean getEnableSessionCreation() { return false; }
        @Override public void bind(SocketAddress bindpoint) {}
        @Override public void connect(SocketAddress endpoint) {}
        @Override public void connect(SocketAddress endpoint, int timeout) {}
        @Override public SocketChannel getChannel() { return null; }
        @Override public InetAddress getInetAddress() { return null; }
        @Override public boolean isClosed() { return false; }
        @Override public void setSoTimeout(int timeout) throws SocketException {}

        @Override
        public synchronized void close() throws IOException {
            input.close();
            output.close();
        }
    }
}
