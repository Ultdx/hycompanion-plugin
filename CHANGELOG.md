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
- In-game validation is still pending.
