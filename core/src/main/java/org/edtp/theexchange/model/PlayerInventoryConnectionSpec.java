package org.edtp.theexchange.model;

import java.util.Optional;

/** Parsed, loader-independent form of {@code <player>@<server>:<password>}. */
public record PlayerInventoryConnectionSpec(String playerName, String serverName,
                                            Optional<String> password) {
    private static final int MAX_PLAYER_NAME_LENGTH = 64;
    private static final int MAX_SERVER_NAME_LENGTH = 64;
    private static final int MAX_PASSWORD_LENGTH = 256;

    public PlayerInventoryConnectionSpec {
        password = password != null ? password : Optional.empty();
    }

    public static PlayerInventoryConnectionSpec parse(String value) {
        if (value == null) {
            throw invalid();
        }
        int at = value.indexOf('@');
        if (at <= 0 || at != value.lastIndexOf('@')) {
            throw invalid();
        }
        int colon = value.indexOf(':', at + 1);
        String playerName = value.substring(0, at).trim();
        String serverName = (colon >= 0
                ? value.substring(at + 1, colon)
                : value.substring(at + 1)).trim();
        String password = colon >= 0 ? value.substring(colon + 1) : null;
        if (playerName.isEmpty() || serverName.isEmpty()
                || playerName.length() > MAX_PLAYER_NAME_LENGTH
                || serverName.length() > MAX_SERVER_NAME_LENGTH
                || (password != null && password.length() > MAX_PASSWORD_LENGTH)) {
            throw invalid();
        }
        return new PlayerInventoryConnectionSpec(playerName, serverName,
                password == null || password.isEmpty() ? Optional.empty() : Optional.of(password));
    }

    public String redacted() {
        return playerName + "@" + serverName;
    }

    @Override
    public String toString() {
        return redacted();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "连接格式无效，应为 <player>@<server>:<password>（密码可省略）");
    }
}
