package org.edtp.theexchange.model;

public class ExchangeMutationResult {
    private final boolean success;
    private final String failReason;
    private final NeutralItem item;

    private ExchangeMutationResult(boolean success, String failReason, NeutralItem item) {
        this.success = success;
        this.failReason = failReason;
        this.item = item;
    }

    public static ExchangeMutationResult success() {
        return new ExchangeMutationResult(true, null, null);
    }

    public static ExchangeMutationResult success(NeutralItem item) {
        return new ExchangeMutationResult(true, null, item);
    }

    public static ExchangeMutationResult fail(String failReason) {
        return new ExchangeMutationResult(false, failReason, null);
    }

    public boolean isSuccess() { return success; }
    public String getFailReason() { return failReason; }
    public NeutralItem getItem() { return item; }
}
