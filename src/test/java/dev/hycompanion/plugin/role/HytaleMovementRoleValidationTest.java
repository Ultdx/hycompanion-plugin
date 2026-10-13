package dev.hycompanion.plugin.role;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.npc.asset.builder.StateMappingHelper;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.FeatureEvaluatorHelper;
import com.hypixel.hytale.server.npc.corecomponents.statemachine.builders.BuilderSensorState;
import com.hypixel.hytale.server.npc.corecomponents.world.SensorReadPosition;
import com.hypixel.hytale.server.npc.corecomponents.world.builders.BuilderSensorReadPosition;
import com.hypixel.hytale.server.npc.sensorinfo.EntityPositionProvider;
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

    @ParameterizedTest
    @MethodSource("roles")
    void moveSensorUsesTheServersCoordinateProviderInsteadOfALiveEntityProvider(boolean legacy, String[] presets)
            throws Exception {
        generatedMoveSensor(legacy, presets);
        JsonObject role = JsonParser.parseString(Files.readString(
                directory.resolve("pack/Server/NPC/Roles/companion.json"))).getAsJsonObject();
        JsonObject target = role.getAsJsonArray("Instructions").get(0).getAsJsonObject()
                .getAsJsonObject("Sensor").getAsJsonArray("Sensors").get(1).getAsJsonObject();
        List<String> errors = new ArrayList<>();
        ServerPositionSensor builder = new ServerPositionSensor(errors);
        builder.readConfig(target);
        assertTrue(errors.isEmpty(), () -> String.join("\n", errors));
        assertEquals("LockedTarget", target.get("Slot").getAsString());
        assertTrue(builder.isUseMarkedTarget(null));
        assertEquals(Double.MAX_VALUE, builder.getRange(null));
        SensorReadPosition sensor = new SensorReadPosition(builder, null);
        assertFalse(sensor.getSensorInfo() instanceof EntityPositionProvider);
        assertNull(sensor.getSensorInfo().getPositionProvider().getTarget());
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void upgradesOldCoordinateTargetsInCachesAndCopiedPacks(boolean cached) throws Exception {
        generatedMoveSensor(false, new String[] {"static", "follow"});
        Path cache = directory.resolve("data/role_cache/companion.json");
        Path asset = directory.resolve("pack/Server/NPC/Roles/companion.json");
        JsonObject expected = JsonParser.parseString(Files.readString(asset)).getAsJsonObject();
        expected.addProperty("Comment", "Keep server settings");
        JsonObject old = expected.deepCopy();
        JsonObject target = old.getAsJsonArray("Instructions").get(0).getAsJsonObject()
                .getAsJsonObject("Sensor").getAsJsonArray("Sensors").get(1).getAsJsonObject();
        target.entrySet().clear();
        target.addProperty("Type", "Target");
        Files.writeString(cached ? cache : asset, old.toString());
        if (!cached) Files.delete(cache);

        generator().loadCachedRoles();
        assertEquals(expected, JsonParser.parseString(Files.readString(asset)));
        if (cached) assertEquals(expected, JsonParser.parseString(Files.readString(cache)));
        String firstLoad = Files.readString(asset);
        generator().loadCachedRoles();
        assertEquals(firstLoad, Files.readString(asset));
    }

    @Test
    void preservesCustomTargetSettingsDuringCoordinateMigration() throws Exception {
        generatedMoveSensor(false, new String[] {"idle", "follow"});
        Path cache = directory.resolve("data/role_cache/companion.json");
        Path asset = directory.resolve("pack/Server/NPC/Roles/companion.json");
        JsonObject custom = JsonParser.parseString(Files.readString(cache)).getAsJsonObject();
        JsonObject target = custom.getAsJsonArray("Instructions").get(0).getAsJsonObject()
                .getAsJsonObject("Sensor").getAsJsonArray("Sensors").get(1).getAsJsonObject();
        target.entrySet().clear();
        target.addProperty("Type", "Target");
        target.addProperty("Range", 120.0);
        String content = custom.toString();
        Files.writeString(cache, content);
        Files.writeString(asset, content);

        assertEquals(1, generator().loadCachedRoles());
        assertEquals(content, Files.readString(cache));
        assertEquals(content, Files.readString(asset));
    }

    private RoleGenerator generator() {
        return new RoleGenerator(directory.resolve("pack"), directory.resolve("data"),
                new PluginLogger("HytaleMovementRoleValidationTest"), "1.1.9", ">=0.7.0");
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

    // Parse the actual server schema; only replace slot lookup, which normally needs a full NPC builder.
    private static final class ServerPositionSensor extends BuilderSensorReadPosition {
        private ServerPositionSensor(List<String> errors) {
            readErrors = errors;
            owner = this;
            evaluatorHelper = new FeatureEvaluatorHelper();
        }

        @Override
        public int getSlot(BuilderSupport support) { return 0; }

        @Override
        public boolean isUseMarkedTarget(BuilderSupport support) { return useMarkedTarget.get(null); }

        @Override
        public double getRange(BuilderSupport support) { return range.get(null); }

        @Override
        public double getMinRange(BuilderSupport support) { return minRange.get(null); }
    }
}
