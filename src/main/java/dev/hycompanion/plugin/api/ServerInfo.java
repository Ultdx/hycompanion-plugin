package dev.hycompanion.plugin.api;

/**
 * Server information sent during connection handshake
 * 
 * @param version     Plugin version
 * @param playerCount Current online player count
 */
public record ServerInfo(
        String version,
        int playerCount) {
    /**
     * Create server info with current values
     */
    public static ServerInfo current(String version, int playerCount) {
        return new ServerInfo(version, playerCount);
    }
}
