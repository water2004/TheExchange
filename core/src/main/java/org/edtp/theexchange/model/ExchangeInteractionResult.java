package org.edtp.theexchange.model;

public class ExchangeInteractionResult {
    public enum Action {
        PASS_TO_LOADER,
        REJECT,
        REFRESH,
        PUT_REMOTE,
        TAKE_REMOTE,
        LOCAL_APPLY
    }

    private final Action action;
    private final String message;
    private final int targetSlot;
    private final int count;
    private final NeutralItem item;

    private ExchangeInteractionResult(Action action, String message,
                                      int targetSlot, int count, NeutralItem item) {
        this.action = action;
        this.message = message;
        this.targetSlot = targetSlot;
        this.count = count;
        this.item = item;
    }

    public static ExchangeInteractionResult passToLoader() {
        return new ExchangeInteractionResult(Action.PASS_TO_LOADER, null, -1, 0, null);
    }

    public static ExchangeInteractionResult reject(String message) {
        return new ExchangeInteractionResult(Action.REJECT, message, -1, 0, null);
    }

    public static ExchangeInteractionResult refresh(String message) {
        return new ExchangeInteractionResult(Action.REFRESH, message, -1, 0, null);
    }

    public static ExchangeInteractionResult putRemote(int slot, NeutralItem item, int count) {
        return new ExchangeInteractionResult(Action.PUT_REMOTE, null, slot, count, item);
    }

    public static ExchangeInteractionResult takeRemote(int slot, int count) {
        return new ExchangeInteractionResult(Action.TAKE_REMOTE, null, slot, count, null);
    }

    public static ExchangeInteractionResult localApply() {
        return new ExchangeInteractionResult(Action.LOCAL_APPLY, null, -1, 0, null);
    }

    public Action getAction() { return action; }
    public String getMessage() { return message; }
    public int getTargetSlot() { return targetSlot; }
    public int getCount() { return count; }
    public NeutralItem getItem() { return item; }
}
