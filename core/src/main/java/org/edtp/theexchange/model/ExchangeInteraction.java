package org.edtp.theexchange.model;

import java.util.ArrayList;
import java.util.List;

public class ExchangeInteraction {
    private final String serverName;
    private final boolean local;
    private final boolean online;
    private final int slotIndex;
    private final int button;
    private final MenuClickType clickType;
    private final NeutralItem slotItem;
    private final NeutralItem carriedItem;
    private final NeutralItem hotbarItem;
    private final List<NeutralItem> exchangeItems;
    private final PlayerExchangeContext player;

    public ExchangeInteraction(String serverName, boolean local, boolean online,
                               int slotIndex, int button, MenuClickType clickType,
                               NeutralItem slotItem, NeutralItem carriedItem,
                               NeutralItem hotbarItem, List<NeutralItem> exchangeItems,
                               PlayerExchangeContext player) {
        this.serverName = serverName;
        this.local = local;
        this.online = online;
        this.slotIndex = slotIndex;
        this.button = button;
        this.clickType = clickType;
        this.slotItem = slotItem;
        this.carriedItem = carriedItem;
        this.hotbarItem = hotbarItem;
        this.exchangeItems = exchangeItems != null ? new ArrayList<>(exchangeItems) : new ArrayList<>();
        this.player = player;
    }

    public String getServerName() { return serverName; }
    public boolean isLocal() { return local; }
    public boolean isOnline() { return online; }
    public int getSlotIndex() { return slotIndex; }
    public int getButton() { return button; }
    public MenuClickType getClickType() { return clickType; }
    public NeutralItem getSlotItem() { return slotItem; }
    public NeutralItem getCarriedItem() { return carriedItem; }
    public NeutralItem getHotbarItem() { return hotbarItem; }
    public List<NeutralItem> getExchangeItems() { return exchangeItems; }
    public PlayerExchangeContext getPlayer() { return player; }
}
