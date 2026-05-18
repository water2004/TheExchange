package org.edtp.theexchange.network.protocol;

/**
 * The exchange network frame.
 * Frame format: Magic(4B) | Version(2B) | Length(4B) | Type(2B) | Sequence(8B) | Timestamp(8B) | Payload(var)
 * Total header: 28 bytes
 */
public class Frame {

    public static final int MAGIC = 0x45584348; // "EXCH"
    public static final short VERSION = 1;
    public static final int HEADER_SIZE = 28;

    private FrameType type;
    private long sequence;
    private long timestamp;
    private byte[] payload;

    public Frame() {}

    public Frame(FrameType type, long sequence, long timestamp, byte[] payload) {
        this.type = type;
        this.sequence = sequence;
        this.timestamp = timestamp;
        this.payload = payload;
    }

    public FrameType getType() { return type; }
    public void setType(FrameType type) { this.type = type; }

    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public byte[] getPayload() { return payload; }
    public void setPayload(byte[] payload) { this.payload = payload; }

    public boolean hasPayload() {
        return payload != null && payload.length > 0;
    }
}
