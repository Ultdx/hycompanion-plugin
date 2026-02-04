package dev.hycompanion.plugin.network.payload;

import dev.hycompanion.plugin.api.ServerInfo;

/**
 * Connection payload sent to backend on connect
 * 
 * @param apiKey     Server API key for authentication
 * @param serverInfo Server information
 */
public record ConnectionPayload(
        String apiKey,
        ServerInfo serverInfo) {
    /**
     * Create connection payload
     */
    public static ConnectionPayload of(String apiKey, String version, int playerCount) {
        return new ConnectionPayload(
                apiKey,
                new ServerInfo(version, playerCount));
    }
}
