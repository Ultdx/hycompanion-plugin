package dev.hycompanion.plugin.role;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hycompanion.plugin.utils.PluginLogger;
import org.junit.jupiter.api.Test;
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
        assertEquals("1.1.8", manifest.get("Version").getAsString());
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
        assertEquals("1.1.8", manifest.get("Version").getAsString());
        assertEquals(">=0.7.0", manifest.get("ServerVersion").getAsString());
        assertTrue(generator.isManifestCreatedThisSession());
    }

    private RoleGenerator generator(Path pack) {
        return new RoleGenerator(pack, directory.resolve("data"), new PluginLogger("RoleGeneratorTest"),
                "1.1.8", ">=0.7.0");
    }

    private JsonObject readManifest(Path pack) throws Exception {
        return JsonParser.parseString(Files.readString(pack.resolve("manifest.json"))).getAsJsonObject();
    }
}
