package org.edtp.theexchange.model;

public class ExchangeInteractionResult {
    public enum Action {
        PASS_TO_LOADER,
        REJECT,
        REFRESH,
        PUT_REMOTE,
        TAKE_REMOTE,
        SWAP_REMOTE
    }

    private final Action action;
    private final String message;
    private final int targetSlot;
    private final int count;
    private final NeutralItem item;
    private final String expectedItemId;
    private final boolean boundedMerge;

    private ExchangeInteractionResult(Action action, String message,
                                      int targetSlot, int count, NeutralItem item,
                                      String expectedItemId, boolean boundedMerge) {
        this.action = action;
        this.message = message;
        this.targetSlot = targetSlot;
        this.count = count;
        this.item = item;
        this.expectedItemId = expectedItemId;
        this.boundedMerge = boundedMerge;
    }

    public static ExchangeInteractionResult passToLoader() {
        return new ExchangeInteractionResult(Action.PASS_TO_LOADER, null, -1, 0, null, null, false);
    }

    public static ExchangeInteractionResult reject(String message) {
        return new ExchangeInteractionResult(Action.REJECT, message, -1, 0, null, null, false);
    }

    public static ExchangeInteractionResult refresh(String message) {
        return new ExchangeInteractionResult(Action.REFRESH, message, -1, 0, null, null, false);
    }

    public static ExchangeInteractionResult putRemote(int slot, NeutralItem item, int count) {
        return new ExchangeInteractionResult(Action.PUT_REMOTE, null, slot, count, item, null, false);
    }

    public static ExchangeInteractionResult takeRemote(int slot, int count) {
        return new ExchangeInteractionResult(Action.TAKE_REMOTE, null, slot, count, null, null, false);
    }

    public static ExchangeInteractionResult swapRemote(int slot, NeutralItem item,
                                                       int count, String expectedItemId) {
        return new ExchangeInteractionResult(Action.SWAP_REMOTE, null, slot, count, item, expectedItemId, false);
    }

    public static ExchangeInteractionResult boundedMergeRemote(int slot, NeutralItem item,
                                                               int count, String expectedItemId) {
        return new ExchangeInteractionResult(Action.SWAP_REMOTE, null, slot, count, item, expectedItemId, true);
    }

    public Action getAction() { return action; }
    public String getMessage() { return message; }
    public int getTargetSlot() { return targetSlot; }
    public int getCount() { return count; }
    public NeutralItem getItem() { return item; }
    public String getExpectedItemId() { return expectedItemId; }
    public boolean isBoundedMerge() { return boundedMerge; }
}
