package dev.hycompanion.plugin.role;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hycompanion.plugin.utils.PluginLogger;
import dev.hycompanion.plugin.core.npc.NpcMovementProgress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RoleGeneratorTest {
    @TempDir
    Path directory;

    @Test
    void updatesAnOldAssetPackWithoutChangingItsCustomFieldsOrRoles() throws Exception {
        Path pack = directory.resolve("pack");
        Path role = pack.resolve("Server/NPC/Roles/companion.json");
        Files.createDirectories(role.getParent());
        Files.writeString(role, "{\"Type\":\"Generic\"}");
        Files.writeString(pack.resolve("manifest.json"), """
                {
                    "Group": "dev.hycompanion",
                    "Name": "Hycompanion",
                    "Version": "1.0.0",
                    "ServerVersion": "=0.5.6",
                    "Description": "Custom role pack description",
                    "Authors": [{"Name": "Server owner"}]
                }
                """);

        RoleGenerator generator = generator(pack);
        generator.ensureModManifest();

        JsonObject manifest = readManifest(pack);
        assertEquals(">=0.7.0", manifest.get("ServerVersion").getAsString());
        assertEquals("1.1.9", manifest.get("Version").getAsString());
        assertEquals("Custom role pack description", manifest.get("Description").getAsString());
        assertEquals("Server owner", manifest.getAsJsonArray("Authors").get(0)
                .getAsJsonObject().get("Name").getAsString());
        assertEquals("{\"Type\":\"Generic\"}", Files.readString(role));
        assertFalse(generator.isManifestCreatedThisSession());

        RoleGenerator nextStartup = generator(pack);
        nextStartup.ensureModManifest();
        assertEquals(manifest, readManifest(pack));
        assertFalse(nextStartup.isManifestCreatedThisSession());
    }

    @Test
    void createsANewPackUsingTheSuppliedLoadedPluginMetadata() throws Exception {
        Path pack = directory.resolve("pack");
        RoleGenerator generator = generator(pack);

        generator.ensureModManifest();

        JsonObject manifest = readManifest(pack);
        assertEquals("dev.hycompanion", manifest.get("Group").getAsString());
        assertEquals("Hycompanion", manifest.get("Name").getAsString());
        assertEquals("1.1.9", manifest.get("Version").getAsString());
        assertEquals(">=0.7.0", manifest.get("ServerVersion").getAsString());
        assertTrue(generator.isManifestCreatedThisSession());
    }

    private RoleGenerator generator(Path pack) {
        return new RoleGenerator(pack, directory.resolve("data"), new PluginLogger("RoleGeneratorTest"),
                "1.1.9", ">=0.7.0");
    }

    @Test
    void coordinateMovementDoesNotInheritThePlayerFollowDistance() throws Exception {
        Path pack = directory.resolve("pack");
        RoleGenerator.NpcRoleData npc = companion(new String[] {"idle", "follow", "wander"});
        npc.parameters = new org.json.JSONObject()
                .put("FollowDistance", new org.json.JSONObject().put("Value", 10.0));
        assertTrue(generator(pack).generateAndCacheRole(npc));
        JsonObject role = readRole(pack);
        JsonObject move = instruction(role, "MoveBehavior");
        JsonObject follow = instruction(role, "FollowBehavior");

        assertEquals(NpcMovementProgress.STATE, move.getAsJsonObject("Sensor")
                .getAsJsonArray("Sensors").get(0).getAsJsonObject().get("State").getAsString());
        JsonObject coordinateSensor = move.getAsJsonObject("Sensor").getAsJsonArray("Sensors")
                .get(1).getAsJsonObject();
        assertEquals("ReadPosition", coordinateSensor.get("Type").getAsString());
        assertEquals("LockedTarget", coordinateSensor.get("Slot").getAsString());
        assertTrue(coordinateSensor.get("UseMarkedTarget").getAsBoolean());
        assertEquals("Target", follow.getAsJsonObject("Sensor").getAsJsonArray("Sensors")
                .get(0).getAsJsonObject().get("Type").getAsString());
        assertTrue(move.getAsJsonObject("BodyMotion").get("StopDistance").getAsDouble()
                < NpcMovementProgress.ARRIVAL_DISTANCE);
        assertFalse(move.getAsJsonObject("BodyMotion").get("AdjustRangeByHitboxSize").getAsBoolean());
        assertEquals(10.0, follow.getAsJsonObject("BodyMotion").get("StopDistance").getAsDouble());
        assertEquals("MoveBehavior", role.getAsJsonArray("Instructions").get(0)
                .getAsJsonObject().get("Name").getAsString());
        assertEquals("Idle", role.get("StartState").getAsString());
    }

    @Test
    void idlePresetStillSupportsExplicitCoordinateMovement() throws Exception {
        Path pack = directory.resolve("pack");
        assertTrue(generator(pack).generateAndCacheRole(companion(new String[] {"idle"})));
        JsonObject role = readRole(pack);
        assertNotNull(instruction(role, "MoveBehavior"));
        assertNull(instruction(role, "FollowBehavior"));
        assertEquals("Idle", role.get("StartState").getAsString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Slothian_Monk", "Skrill_Chick"})
    void affectedNpcProfilesKeepPlayerFollowingAndUseCoordinateNavigation(String appearance) throws Exception {
        RoleGenerator.NpcRoleData npc = companion(new String[] {"static", "follow"});
        npc.roleType = "Variant";
        npc.appearance = appearance;
        npc.motionControllers = new org.json.JSONObject("""
                {"Walk":{"type":"Walk","maxSpeed":5,"turnSpeed":180,"jumpHeight":1.2,"acceleration":25}}
                """);
        Path pack = directory.resolve("pack");
        assertTrue(generator(pack).generateAndCacheRole(npc));
        JsonObject role = readRole(pack);
        assertEquals(appearance, role.get("Appearance").getAsString());
        JsonObject controller = role.getAsJsonArray("MotionControllerList").get(0).getAsJsonObject();
        assertEquals("Walk", controller.get("Type").getAsString());
        assertEquals(5.0, controller.get("MaxWalkSpeed").getAsDouble());
        JsonObject move = instruction(role, "MoveBehavior");
        assertEquals("ReadPosition", move.getAsJsonObject("Sensor").getAsJsonArray("Sensors")
                .get(1).getAsJsonObject().get("Type").getAsString());
        JsonObject follow = instruction(role, "FollowBehavior");
        assertEquals("Target", follow.getAsJsonObject("Sensor").getAsJsonArray("Sensors")
                .get(0).getAsJsonObject().get("Type").getAsString());
    }

    private RoleGenerator.NpcRoleData companion(String[] presets) {
        RoleGenerator.NpcRoleData npc = new RoleGenerator.NpcRoleData();
        npc.externalId = "companion";
        npc.displayName = "Villager";
        npc.reference = "Template_Hycompanion_NPC";
        npc.behaviorPresets = presets;
        return npc;
    }

    private JsonObject readRole(Path pack) throws Exception {
        return JsonParser.parseString(Files.readString(pack.resolve("Server/NPC/Roles/companion.json")))
                .getAsJsonObject();
    }

    private JsonObject instruction(JsonObject role, String name) {
        for (var instruction : role.getAsJsonArray("Instructions")) {
            JsonObject object = instruction.getAsJsonObject();
            if (name.equals(object.get("Name").getAsString())) return object;
        }
        return null;
    }

    private JsonObject readManifest(Path pack) throws Exception {
        return JsonParser.parseString(Files.readString(pack.resolve("manifest.json"))).getAsJsonObject();
    }
}
