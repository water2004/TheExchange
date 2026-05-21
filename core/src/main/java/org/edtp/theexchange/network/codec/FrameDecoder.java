package org.edtp.theexchange.network.codec;

import org.edtp.theexchange.network.protocol.Frame;
import org.edtp.theexchange.network.protocol.FrameType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Handles TCP framing (sticky packet / half-packet) and frame decoding.
 * Call readFrame() repeatedly until null is returned (needs more data).
 */
public class FrameDecoder {

    public static final int MAX_FRAME_SIZE = 10 * 1024 * 1024;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean headerRead;
    private int expectedLength;
    private FrameType expectedType;
    private long sequence;
    private long timestamp;

    /**
     * Feed received bytes. Returns a completed Frame or null if more data is needed.
     */
    public Frame feed(byte[] data) throws IOException {
        return feed(data, 0, data.length);
    }

    public Frame feed(byte[] data, int offset, int length) throws IOException {
        buffer.write(data, offset, length);

        if (!headerRead) {
            if (buffer.size() < Frame.HEADER_SIZE) return null;

            byte[] header = buffer.toByteArray();
            ByteBuffer bb = ByteBuffer.wrap(header, 0, Frame.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);

            int magic = bb.getInt();
            if (magic != Frame.MAGIC) {
                throw new IOException("Invalid magic: " + Integer.toHexString(magic));
            }

            short version = bb.getShort();
            expectedLength = bb.getInt();
            expectedType = FrameType.fromCode(bb.getShort());
            sequence = bb.getLong();
            timestamp = bb.getLong();

            if (expectedLength < 0 || expectedLength > MAX_FRAME_SIZE) {
                throw new IOException("Frame too large: " + expectedLength);
            }

            headerRead = true;
            // Reset buffer to just the payload portion
            byte[] remaining = new byte[buffer.size() - Frame.HEADER_SIZE];
            System.arraycopy(header, Frame.HEADER_SIZE, remaining, 0, remaining.length);
            buffer.reset();
            buffer.write(remaining);
        }

        if (buffer.size() < expectedLength) return null;

        byte[] allBytes = buffer.toByteArray();
        byte[] payload = new byte[expectedLength];
        System.arraycopy(allBytes, 0, payload, 0, expectedLength);

        // Reset for next frame: any bytes beyond this frame go into the next buffer
        int remaining = allBytes.length - expectedLength;
        buffer.reset();
        if (remaining > 0) {
            buffer.write(allBytes, expectedLength, remaining);
        }
        headerRead = false;

        Frame frame = new Frame(expectedType, sequence, timestamp, payload);
        return frame;
    }

    /**
     * Convenience: read a single frame from an InputStream. Blocks until a complete frame arrives.
     */
    public static Frame readFrame(InputStream in) throws IOException {
        byte[] headerBytes = new byte[Frame.HEADER_SIZE];
        int read = readFully(in, headerBytes);
        if (read < Frame.HEADER_SIZE) return null;

        ByteBuffer bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN);
        int magic = bb.getInt();
        if (magic != Frame.MAGIC) {
            throw new IOException("Invalid magic: " + Integer.toHexString(magic));
        }
        bb.getShort(); // version
        int length = bb.getInt();
        if (length < 0 || length > MAX_FRAME_SIZE) {
            throw new IOException("Frame too large: " + length);
        }
        FrameType type = FrameType.fromCode(bb.getShort());
        long sequence = bb.getLong();
        long timestamp = bb.getLong();

        byte[] payload = null;
        if (length > 0) {
            payload = new byte[length];
            readFully(in, payload);
        }

        return new Frame(type, sequence, timestamp, payload);
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    public void reset() {
        buffer.reset();
        headerRead = false;
    }
}
