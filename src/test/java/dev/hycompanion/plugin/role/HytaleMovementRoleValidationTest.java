package dev.hycompanion.plugin.role;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.npc.asset.builder.StateMappingHelper;
import com.hypixel.hytale.server.npc.corecomponents.statemachine.builders.BuilderSensorState;
import dev.hycompanion.plugin.core.npc.NpcMovementProgress;
import dev.hycompanion.plugin.utils.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HytaleMovementRoleValidationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @MethodSource("roles")
    void generatedMoveStatePassesTheServerValidator(boolean legacy, String[] presets) throws Exception {
        JsonObject sensor = generatedMoveSensor(legacy, presets);
        List<String> errors = validateWithServer(sensor);

        assertTrue(errors.isEmpty(), () -> String.join("\n", errors));
    }

    static Stream<Arguments> roles() {
        return Stream.of(false, true).flatMap(legacy -> Stream.of(
                Arguments.of(legacy, new String[] {"idle"}),
                Arguments.of(legacy, new String[] {"idle", "follow"}),
                Arguments.of(legacy, new String[] {"idle", "follow", "wander"})));
    }

    @Test
    void serverValidatorReproducesTheReportedErrorWhenExternalSetterIsNotDeclared() throws Exception {
        JsonObject sensor = generatedMoveSensor(false, new String[] {"idle", "follow"});
        sensor.remove("IgnoreMissingSetState");

        List<String> errors = validateWithServer(sensor);

        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().endsWith(
                "State sensor or State setter action/motion exists without accompanying state/setter: Move"));
    }

    @Test
    void repairsCachedRolesBeforeLoadingAndPreservesOtherSettings() throws Exception {
        generatedMoveSensor(false, new String[] {"idle", "follow", "wander"});
        Path cache = directory.resolve("data/role_cache/companion.json");
        JsonObject oldRole = JsonParser.parseString(Files.readString(cache)).getAsJsonObject();
        JsonObject oldSensor = moveSensor(oldRole);
        oldSensor.remove("IgnoreMissingSetState");
        oldRole.addProperty("Comment", "Keep existing server settings");
        Files.writeString(cache, oldRole.toString());
        Path asset = directory.resolve("pack/Server/NPC/Roles/companion.json");
        Files.delete(asset);

        RoleGenerator generator = generator();
        assertEquals(1, generator.loadCachedRoles());
        JsonObject upgraded = JsonParser.parseString(Files.readString(asset)).getAsJsonObject();
        oldSensor.addProperty("IgnoreMissingSetState", true);
        assertEquals(oldRole, upgraded);
        assertEquals(upgraded, JsonParser.parseString(Files.readString(cache)));
        assertTrue(validateWithServer(moveSensor(upgraded)).isEmpty());
        String firstLoad = Files.readString(cache);
        assertEquals(1, generator.loadCachedRoles());
        assertEquals(firstLoad, Files.readString(cache));
    }

    @Test
    void doesNotRewriteCustomMovementInstructionsInTheCache() throws Exception {
        generatedMoveSensor(false, new String[] {"idle"});
        Path cache = directory.resolve("data/role_cache/companion.json");
        JsonObject customRole = JsonParser.parseString(Files.readString(cache)).getAsJsonObject();
        moveSensor(customRole).remove("IgnoreMissingSetState");
        customRole.getAsJsonArray("Instructions").get(0).getAsJsonObject()
                .addProperty("Name", "CustomMovement");
        String content = customRole.toString();
        Files.writeString(cache, content);

        assertEquals(1, generator().loadCachedRoles());
        assertEquals(content, Files.readString(cache));
        assertEquals(content, Files.readString(directory.resolve("pack/Server/NPC/Roles/companion.json")));
    }

    @Test
    void repairsACopiedAssetPackWithoutACache() throws Exception {
        generatedMoveSensor(false, new String[] {"idle"});
        Path asset = directory.resolve("pack/Server/NPC/Roles/companion.json");
        JsonObject oldRole = JsonParser.parseString(Files.readString(asset)).getAsJsonObject();
        moveSensor(oldRole).remove("IgnoreMissingSetState");
        Files.writeString(asset, oldRole.toString());
        Files.delete(directory.resolve("data/role_cache/companion.json"));
        Files.delete(directory.resolve("data/role_cache"));

        assertEquals(0, generator().loadCachedRoles());
        JsonObject upgraded = JsonParser.parseString(Files.readString(asset)).getAsJsonObject();
        assertTrue(validateWithServer(moveSensor(upgraded)).isEmpty());
        moveSensor(oldRole).addProperty("IgnoreMissingSetState", true);
        assertEquals(oldRole, upgraded);
    }

    private RoleGenerator generator() {
        return new RoleGenerator(directory.resolve("pack"), directory.resolve("data"),
                new PluginLogger("HytaleMovementRoleValidationTest"), "1.1.8", ">=0.7.0");
    }

    private JsonObject generatedMoveSensor(boolean legacy, String[] presets) throws Exception {
        Path pack = directory.resolve("pack");
        RoleGenerator generator = generator();
        RoleGenerator.NpcRoleData npc = new RoleGenerator.NpcRoleData();
        npc.externalId = "companion";
        npc.displayName = "Villager";
        npc.roleType = legacy ? null : "Variant";
        npc.reference = legacy ? null : "Template_Hycompanion_NPC";
        npc.behaviorPresets = presets;
        assertTrue(generator.generateAndCacheRole(npc));
        JsonObject role = JsonParser.parseString(Files.readString(
                pack.resolve("Server/NPC/Roles/companion.json"))).getAsJsonObject();
        return moveSensor(role);
    }

    private JsonObject moveSensor(JsonObject role) {
        return role.getAsJsonArray("Instructions").get(0).getAsJsonObject()
                .getAsJsonObject("Sensor").getAsJsonArray("Sensors").get(0).getAsJsonObject();
    }

    private List<String> validateWithServer(JsonObject sensor) {
        StateMappingHelper states = new StateMappingHelper();
        states.setDefaultSubState("Default");
        states.setNotComponent();
        List<String> errors = new ArrayList<>();
        new ServerStateSensor(states, errors).readConfig(sensor);
        assertTrue(errors.isEmpty(), () -> "Sensor parsing failed: " + errors);
        assertTrue(states.getStateIndex(NpcMovementProgress.STATE) >= 0);
        states.validate("companion.json", errors);
        return errors;
    }

    // Supply only the builder context; parsing and state validation use the actual server JAR.
    private static final class ServerStateSensor extends BuilderSensorState {
        private ServerStateSensor(StateMappingHelper states, List<String> errors) {
            stateHelper = states;
            readErrors = errors;
            owner = this;
        }
    }
}
