# Changelog

## Unreleased — 2026-10-12

### Changed

- Target Hytale Server 0.7.0-pre.5.1 and declare `>=0.7.0-pre.5.1` in the plugin manifest.
- Access NPC state, follow targets and combat support through ECS components.
- Read blocks and fluids through `SectionReader`, skipping unloaded sections.
- Use section references, including the target Y coordinate, for block damage and placement.
- Install `libs/HytaleServer.jar` into the local Maven repository before running the Windows build.
- Add `compile-plugin.bat --skip-sentry` and the Maven `sentry.skip` property for local builds without source uploads.
- Document the supported server API and the Hytale plugin JAR to install.

### Fixed

- Adapt block damage calls to the new impact entity and explosion parameters.
- Access live container components so inventory transfers update the actual block inventory.
- Include the `PlaceResult` record required by the existing block placement API.

### Validation

- `compile-plugin.bat --skip-sentry` and `mvn -o clean verify -Dsentry.skip=true` succeeded.
- Verified the packaged manifest, server compatibility range and loading of all 72 plugin classes against the supplied server JAR.
- In-game validation is still pending; the repository has no automated tests.
