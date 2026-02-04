package dev.hycompanion.plugin.core.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * World context data sent with chat events
 * 
 * Provides environmental information to enrich NPC responses
 * 
 * @param location      Player location as "x,y,z" string
 * @param timeOfDay     Current time of day (dawn, morning, noon, afternoon,
 *                      dusk, night)
 * @param weather       Current weather (clear, rain, storm, snow)
 * @param nearbyPlayers List of nearby player names
 */
public record WorldContext(
        String location,
        String timeOfDay,
        String weather,
        List<String> nearbyPlayers) {

    /**
     * Convert to JSON for socket payload
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();

        if (location != null) {
            json.addProperty("location", location);
        }

        if (timeOfDay != null) {
            json.addProperty("timeOfDay", timeOfDay);
        }

        if (weather != null) {
            json.addProperty("weather", weather);
        }

        if (nearbyPlayers != null && !nearbyPlayers.isEmpty()) {
            JsonArray playersArray = new JsonArray();
            nearbyPlayers.forEach(playersArray::add);
            json.add("nearbyPlayers", playersArray);
        }

        return json;
    }

    /**
     * Create a minimal context with just location
     */
    public static WorldContext minimal(String location) {
        return new WorldContext(location, null, null, List.of());
    }

    /**
     * Create a default context with mock values
     */
    public static WorldContext defaultContext() {
        return new WorldContext(
                "0,64,0",
                "noon",
                "clear",
                List.of());
    }
}
