package org.edtp.theexchange.network.protocol;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum FrameType {
    AUTH_REQUEST((short) 0x0001),
    AUTH_RESPONSE((short) 0x0002),
    HEARTBEAT((short) 0x0003),
    PLAYER_INVENTORY_ACCESS((short) 0x0004),
    PLAYER_INVENTORY_ACCESS_RESPONSE((short) 0x0005),
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
    MUTATION_EXECUTE((short) 0x0020),
    MUTATION_RECOVER((short) 0x0021),
    MUTATION_RESULT((short) 0x0022),
    TRANSACTION_QUERY((short) 0x0023),
    TRANSACTION_STATUS((short) 0x0024),
    TRANSACTION_SETTLED((short) 0x0025),
    TRANSACTION_CLOSED((short) 0x0026),
    PUSH_UPDATE((short) 0x0030),
    ERROR((short) 0xFFFF);

    private static final Map<Short, FrameType> BY_CODE = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(FrameType::getCode, type -> type));

    private final short code;

    FrameType(short code) {
        this.code = code;
    }

    public short getCode() {
        return code;
    }

    public static FrameType fromCode(short code) {
        return BY_CODE.get(code);
    }

    public boolean isMutationLifecycle() {
        return switch (this) {
            case MUTATION_EXECUTE, MUTATION_RECOVER, MUTATION_RESULT,
                    TRANSACTION_QUERY, TRANSACTION_STATUS,
                    TRANSACTION_SETTLED, TRANSACTION_CLOSED -> true;
            default -> false;
        };
    }
}
