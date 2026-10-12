# Changelog

## Unreleased — 2026-10-12

### Changed

- Target the official Hytale Server 0.7.0 release and declare `>=0.7.0` in the plugin manifest.
- Access NPC state, follow targets and combat support through ECS components.
- Read blocks and fluids through `SectionReader`, skipping unloaded sections.
- Use section references, including the target Y coordinate, for block damage and placement.
- Resolve the official server API from Maven without overwriting it with a local pre-release JAR.
- Add `compile-plugin.bat --skip-sentry` and the Maven `sentry.skip` property for local builds without source uploads.
- Document the supported server API and the Hytale plugin JAR to install.

### Fixed

- Fix coordinate movement by using a stationary, intangible server-side target with an explicit bounding box instead of an asset-dependent projectile marker. Resolve the NPC's current world from its entity store.
- Give explicit `move_to` commands their own `Move` behavior with a 1-block stopping distance, independent of the configured player follow distance and available idle/follow/wander presets.
- Remove the false arrival shortcut for NPCs stuck within 5 blocks. Report success only within 1.5 blocks of the requested destination, with the actual position, remaining distance and arrival tolerance.
- Verify movement state and target assignment, and report missing roles or lost markers explicitly instead of waiting for a misleading movement result.
- Restore nearby block, mushroom, fluid and container detection in partially loaded columns: check the individual section instead of `hasLoadedSections`, which requires more than 10 loaded sections in Hytale 0.7.0.
- Respect the world's supported Y coordinates during scans, including blocks above Y=255 and negative coordinates in cubic worlds.
- Report unavailable block/entity scans as errors instead of treating them as an empty area.
- Adapt block damage calls to the new impact entity and explosion parameters.
- Access live container components so inventory transfers update the actual block inventory.
- Include the `PlaceResult` record required by the existing block placement API.
- Synchronize the dynamic role pack's version and server range from the loaded plugin manifest, avoiding stale or shadowed `manifest.json` resources.
- Log asset pack compatibility updates with restart guidance for warnings emitted during discovery.
- Create the dynamic role pack directory before writing its manifest on first startup.

### Validation

- `compile-plugin.bat --skip-sentry` and `mvn -o clean verify -Dsentry.skip=true` succeeded.
- Verified the packaged manifest, server compatibility range and loading of all 72 plugin classes against the official server API.
- Regression tests cover upgrading an old asset pack manifest, preserving custom metadata and role files, and creating a manifest on first startup.
- `mvn -o verify -Dsentry.skip=true` passed all 11 tests and rebuilt the Hytale JAR. Movement tests replay the stationary positions from the mushroom-gathering logs and check real progress, stalls, vertical distance, and independence from player follow distance.
- Verified the scan availability and supported height semantics against the official 0.7.0 server bytecode; backend regression tests cover mushrooms, chests, dropped items and scan failures.
- In-game validation is still pending.
