package org.edtp.theexchange;

import org.edtp.theexchange.api.ExchangeAPI;
import org.edtp.theexchange.compat.ItemSerializer;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class TheExchangeCoreLifecycleTest {

    @Test
    void shutdownIsSafeAfterInitializationFailedBeforeLocalStoreCreation() {
        TheExchangeCore core = new TheExchangeCore(new NoOpApi());
        assertDoesNotThrow(core::shutdown);
    }

    private static final class NoOpApi implements ExchangeAPI {
        private static final Logger LOGGER = new Logger() {
            @Override public void info(String message) {}
            @Override public void warn(String message) {}
            @Override public void error(String message) {}
            @Override public void error(String message, Throwable t) {}
        };

        @Override public Logger getLogger() { return LOGGER; }
        @Override public ItemSerializer getItemSerializer() { return null; }
        @Override public ConfigLoader getConfigLoader() { return null; }
        @Override public String getServerVersion() { return "test"; }
        @Override public String getServerName() { return "test"; }
        @Override public void runOnMainThread(Runnable task) { task.run(); }
        @Override public void runAsync(Runnable task) { task.run(); }
        @Override public Optional<PlayerIdentity> resolvePlayerIdentity(String playerName) {
            return Optional.empty();
        }
        @Override public void refreshRemoteInventoryView(String serverName) {}
        @Override public void redrawRemoteInventoryView(String serverName) {}
    }
}
