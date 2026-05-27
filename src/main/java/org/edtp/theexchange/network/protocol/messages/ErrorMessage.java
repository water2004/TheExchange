package org.edtp.theexchange.network.protocol.messages;

public class ErrorMessage {
    private int code;
    private String message;

    public ErrorMessage() {}

    public ErrorMessage(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    // Standard error codes
    public static final int AUTH_FAILED = 401;
    public static final int PERMISSION_DENIED = 403;
    public static final int ITEM_NOT_FOUND = 404;
    public static final int CONFLICT = 409;
    public static final int RATE_LIMITED = 429;
    public static final int INTERNAL_ERROR = 500;
    public static final int SERVER_OFFLINE = 503;
}
