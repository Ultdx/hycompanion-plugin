# Changelog

## 1.1.9 — 2026-10-13

### Changed

- Bump the plugin version from 1.1.8 to 1.1.9, matching the Maven artifact, packaged manifests, runtime version and documentation. Keep the Hytale Server API at 0.7.0.
- Report the loaded plugin version when fetching NPC roles during startup instead of a separate hardcoded snapshot version.

### Fixed

- Remove `Intangible` from the stationary coordinate movement target. Hytale's pre-behavior tick rejects intangible marked entities and clears `LockedTarget` before `MoveBehavior` can run, causing an idle NPC, `motion=null` and `targetLocked=false` until timeout. The target still has no model or network components.
- Feed coordinate `move_to` targets through Hytale's native `ReadPosition` sensor, avoiding the live-entity movement requirements of a stationary marker. Keep player following on its existing `Target` sensor.
- Upgrade unconfigured generated `MoveBehavior` targets in cached roles and copied asset packs before loading. Preserve custom target settings and other role data.
- Allow up to 10 seconds for initial path computation while the engine reports `PROGRESSING`; retain the 3-second stall timeout after movement starts or when navigation is not progressing.
- Include the selected steering motion, navigation state and target lock in movement timeout logs.
- Declare the externally controlled `Move` state with `IgnoreMissingSetState`, fixing Hytale's "State sensor or State setter action/motion exists without accompanying state/setter: Move" validation error introduced by the coordinate movement update.
- Repair existing generated `MoveBehavior` roles in the asset pack and cache during setup, before NPC asset validation, including copied packs and offline starts. Preserve other role settings and custom instructions.

### Validation

- Verified release 1.1.9 in the packaged JSON/YAML metadata and both compiled entry points; Hytale compatibility remains `>=0.7.0`.
- Reproduced the reported error using the official 0.7.0 `BuilderSensorState` and `StateMappingHelper`, then verified the corrected sensor across standalone and legacy roles with idle, follow and wander presets.
- Reproduced rejection of the old movement marker with the official 0.7.0 `EntityDetectionUtil.isDetectableByNPCs` method and verified that the production marker factory passes the same rule. Tests also verify the destination, UUID and bounding box.
- `mvn -o verify -Dsentry.skip=true` passed all 43 tests and rebuilt the Hytale plugin JAR. Tests cover target detectability, the official server's coordinate sensor parser and position provider, both affected NPC profiles, bounded path startup, cached and copied pack upgrades, custom settings and repeat startup.
- Reviewed the official 0.7.0 source chain for state validation, state changes, marked targets, target position providers, combined sensors and Seek movement; the new extraction's JAR matches the installed Maven API and `libs/HytaleServer.jar`.
- Server logs from 2026-10-13 confirm coordinate arrival, block breaking and successful item pickup. The first pickup used the block ID instead of its dropped item ID; the backend correction addresses that separate gathering failure. Collection on the first request needs a retest with the updated backend.

## Migration work — 2026-10-12

### Changed

- Target the official Hytale Server 0.7.0 release and declare `>=0.7.0` in the plugin manifest.
- Access NPC state, follow targets and combat support through ECS components.
- Read blocks and fluids through `SectionReader`, skipping unloaded sections.
- Use section references, including the target Y coordinate, for block damage and placement.
- Resolve the official server API from Maven without overwriting it with a local pre-release JAR.
- Add `compile-plugin.bat --skip-sentry` and the Maven `sentry.skip` property for local builds without source uploads.
- Document the supported server API and the Hytale plugin JAR to install.

### Fixed

- Keep each movement's marker, monitor and pending result together. Replacement, player following, stopping, NPC removal, teleportation and shutdown now interrupt the movement explicitly; queued callbacks cannot clean up a newer command.
- Restore compatibility with cached or inherited roles that expose `Follow` without the new `Move` state. Arrival still requires the observed position to be within 1.5 blocks; reload generated roles for the precise 1-block stopping distance.
- Resolve player follow and stop commands in the NPC's current world, matching coordinate movement even when cached spawn metadata is stale.
- Use a stationary server-side target with an explicit bounding box instead of an asset-dependent projectile marker. Resolve the NPC's current world from its entity store.
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
- `mvn -o verify -Dsentry.skip=true` passed all 17 tests and rebuilt the Hytale JAR. Movement tests replay the stationary positions from the mushroom-gathering logs and check progress, stalls, vertical distance, independence from player follow distance, legacy state selection, interruption, late resource registration and cleanup before completion callbacks.
- Compared nine key movement/state/target classes byte-for-byte between the supplied pre-release JAR and the official 0.7.0 JAR; all nine match. The official source review followed on 2026-10-13.
- Verified the scan availability and supported height semantics against the official 0.7.0 server bytecode; backend regression tests cover mushrooms, chests, dropped items and scan failures.
- In-game validation is still pending.
