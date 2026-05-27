package org.edtp.theexchange.network.codec;

import org.edtp.theexchange.network.protocol.Frame;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class FrameEncoder {

    private FrameEncoder() {}

    public static byte[] encode(Frame frame) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {

            dos.writeInt(Frame.MAGIC);
            dos.writeShort(Frame.VERSION);
            byte[] payload = frame.getPayload();
            int length = payload != null ? payload.length : 0;
            dos.writeInt(length);
            dos.writeShort(frame.getType().getCode());
            dos.writeLong(frame.getSequence());
            dos.writeLong(frame.getTimestamp());

            if (payload != null && payload.length > 0) {
                dos.write(payload);
            }

            dos.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode frame", e);
        }
    }
}
