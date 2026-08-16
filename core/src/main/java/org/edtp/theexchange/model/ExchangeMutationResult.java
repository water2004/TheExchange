package org.edtp.theexchange.model;

import java.util.concurrent.atomic.AtomicBoolean;

public class ExchangeMutationResult {
    private final boolean success;
    private final String failReason;
    private final NeutralItem item;
    private final Runnable settlement;
    private final AtomicBoolean settled = new AtomicBoolean();

    private ExchangeMutationResult(boolean success, String failReason, NeutralItem item,
                                   Runnable settlement) {
        this.success = success;
        this.failReason = failReason;
        this.item = item;
        this.settlement = settlement != null ? settlement : () -> {};
    }

    public static ExchangeMutationResult success() {
        return success(null, () -> {});
    }

    public static ExchangeMutationResult success(NeutralItem item) {
        return success(item, () -> {});
    }

    public static ExchangeMutationResult success(NeutralItem item, Runnable settlement) {
        return new ExchangeMutationResult(true, null, item, settlement);
    }

    public static ExchangeMutationResult fail(String failReason) {
        return fail(failReason, () -> {});
    }

    public static ExchangeMutationResult fail(String failReason, Runnable settlement) {
        return new ExchangeMutationResult(false, failReason, null, settlement);
    }

    public boolean isSuccess() { return success; }
    public String getFailReason() { return failReason; }
    public NeutralItem getItem() { return item; }

    /** Complete the remote transaction after the platform has settled its local item side. */
    public void acknowledgeSettlement() {
        if (settled.compareAndSet(false, true)) {
            settlement.run();
        }
    }
}
