package org.edtp.theexchange.network.codec;

import org.edtp.theexchange.network.protocol.Frame;
import org.edtp.theexchange.network.protocol.FrameType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameProtocolVersionTest {

    @Test
    void protocolV1FrameIsRejected() {
        FrameDecoder decoder = new FrameDecoder();
        IOException error = assertThrows(IOException.class, () -> decoder.feed(frameHeader((short) 1)));
        assertEquals("Protocol version mismatch: expected 2, got 1", error.getMessage());
    }

    @Test
    void protocolV2FrameIsAccepted() throws IOException {
        Frame decoded = new FrameDecoder().feed(frameHeader(Frame.VERSION));
        assertEquals(FrameType.HEARTBEAT, decoded.getType());
    }

    @Test
    void unknownV2FrameTypeIsRejectedInsteadOfDecodedAsError() {
        byte[] frame = frameHeader(Frame.VERSION);
        ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putShort(10, (short) 0x7777);

        assertThrows(IOException.class, () -> new FrameDecoder().feed(frame));
    }

    private byte[] frameHeader(short version) {
        return ByteBuffer.allocate(Frame.HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(Frame.MAGIC)
                .putShort(version)
                .putInt(0)
                .putShort(FrameType.HEARTBEAT.getCode())
                .putLong(1L)
                .putLong(System.currentTimeMillis())
                .array();
    }
}
