package org.edtp.theexchange.network.protocol;

public enum FrameType {
    AUTH_REQUEST((short) 0x0001),
    AUTH_RESPONSE((short) 0x0002),
    HEARTBEAT((short) 0x0003),
    QUERY_TIMESTAMP((short) 0x0010),
    TIMESTAMP_RESPONSE((short) 0x0011),
    QUERY_ITEMS((short) 0x0012),
    ITEMS_RESPONSE((short) 0x0013),
    QUERY_SLOT_VERSION((short) 0x0014),
    SLOT_VERSION_RESPONSE((short) 0x0015),
    QUERY_SLOT_STATE((short) 0x0016),
    SLOT_STATE_RESPONSE((short) 0x0017),
    QUERY_SLOT_VERSIONS((short) 0x0018),
    SLOT_VERSIONS_RESPONSE((short) 0x0019),
    QUERY_SLOTS((short) 0x001A),
    SLOTS_STATE_RESPONSE((short) 0x001B),
    PUT_ITEM((short) 0x0020),
    PUT_ITEM_RESPONSE((short) 0x0021),
    TAKE_ITEM((short) 0x0022),
    TAKE_ITEM_RESPONSE((short) 0x0023),
    PUSH_UPDATE((short) 0x0030),
    ERROR((short) (short) 0xFFFF);

    private final short code;

    FrameType(short code) {
        this.code = code;
    }

    public short getCode() {
        return code;
    }

    public static FrameType fromCode(short code) {
        for (FrameType type : values()) {
            if (type.code == code) return type;
        }
        return ERROR;
    }
}
