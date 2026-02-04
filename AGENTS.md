# Hycompanion Plugin - Agent Guide

This file provides context and guidelines for AI agents working on the Hycompanion Plugin.

## Project Overview

**Hycompanion Plugin** is an AI-powered NPC companion system for Hytale servers. It connects Hytale servers to a cloud backend (Socket.IO) to enable intelligent, context-aware NPC interactions using LLMs.

- **License:** MIT (both plugin and backend)
- **Language:** Java 25 (OpenJDK Temurin)
- **Build Tool:** Maven 3.9+
- **Target Platform:** Hytale Server (plugin) / Node.js (backend)

## Architecture

```
┌─────────────────┐         ┌──────────────────┐         ┌─────────────────┐
│   Hytale Game   │◄───────►│   Hycompanion    │◄───────►│    Backend      │
│     Server      │         │     Plugin       │         │   (Socket.IO)   │
│                 │ Events  │                  │  JSON   │                 │
│  - Chat Events  │────────►│  - ChatHandler   │────────►│  - LLM (GPT)    │
│  - NPC Entities │◄────────│  - ActionExec    │◄────────│  - Memory       │
│  - Trade/Quest  │ Actions │  - NpcManager    │ Actions │  - MCP Tools    │
└─────────────────┘         └──────────────────┘         └─────────────────┘
```

## Project Structure

```
.
├── .env.example                 # Environment variables template
├── compile-plugin.bat           # Windows build script
├── pom.xml                      # Maven configuration
├── LICENSE                      # MIT License
├── README.md                    # User documentation
├── AGENTS.md                    # This file
└── src/main/java/dev/hycompanion/plugin/
    ├── HycompanionEntrypoint.java   # Hytale Server entry point
    ├── HycompanionPlugin.java       # Standalone testing entry point
    ├── api/                        # Hytale API abstraction (interfaces)
    ├── adapter/                    # API implementations
    │   ├── HytaleServerAdapter.java # Real Hytale Server API
    │   └── MockHytaleAdapter.java   # Mock for standalone testing
    ├── commands/                   # Hytale commands
    ├── config/                     # Configuration management
    ├── core/                       # Core business logic
    │   ├── context/                # World context building
    │   ├── npc/                    # NPC data and management
    │   └── world/                  # Block/world utilities
    ├── handlers/                   # Event handlers (chat, actions)
    ├── network/                    # Socket.IO networking
    ├── role/                       # NPC role file generation
    ├── shutdown/                   # Graceful shutdown handling
    ├── systems/                    # ECS systems (respawn)
    └── utils/                      # Utilities (logging)
```

## Key Technologies

- **Java 25** - Target version, uses preview features
- **Maven** - Build and dependency management
- **Socket.IO Client** - Real-time backend communication
- **Gson** - JSON serialization
- **Sentry** - Error tracking (optional, configured via env vars)

## Building the Project

### Quick Build (Windows)
```bash
compile-plugin.bat
```

### Manual Build
```bash
# Set environment variables (optional)
set SENTRY_AUTH_TOKEN=your_token_here

# Compile and package
mvn clean package

# Output: target/hycompanion-plugin-1.1.0-SNAPSHOT-jar-with-dependencies.jar
```

### Environment Variables
Create a `.env` file (see `.env.example`):
- `SENTRY_DSN` - Optional, for error tracking
- `SENTRY_AUTH_TOKEN` - Optional, for build-time source uploads

### Sentry Properties (Runtime Alternative)
Copy `sentry.properties.example` to `sentry.properties` for runtime Sentry config (alternative to env var). This file is gitignored.

## Coding Guidelines

### Java Version
- **Minimum:** Java 25
- **Features used:** Virtual Threads, Records, Pattern Matching, Text Blocks
- **Enable preview:** `--enable-preview` flag required

### Code Style
- Use **records** for data classes (DTOs, config)
- Use **Optional** for nullable returns
- Prefer **Virtual Threads** (`Thread.ofVirtual()`) for async operations
- Use **ConcurrentHashMap** for thread-safe collections
- Follow **SLF4J** logging patterns via `PluginLogger`

### Hytale API Integration
- All Hytale-specific code lives in `adapter.HytaleServerAdapter`
- The `api.HytaleAPI` interface abstracts Hytale dependencies
- `MockHytaleAdapter` allows standalone testing without Hytale server

### Error Handling
- Catch exceptions at boundaries (event handlers, socket events)
- Use `Sentry.captureException(e)` for unexpected errors (if Sentry configured)
- Log with appropriate levels: `debug`, `info`, `warn`, `error`
- During shutdown, be defensive - catch all errors to prevent crashes

### Thread Safety
- Hytale APIs must be called on **WorldThread**
- Use `world.execute(() -> { ... })` for world operations
- Check `Thread.currentThread().getName().contains("WorldThread")` if unsure
- Background tasks use Virtual Threads or daemon thread pools

## Configuration

### config.yml (Runtime)
Located in `plugins/Hycompanion/config.yml`:
```yaml
connection:
  url: "https://api.hycompanion.dev"
  api_key: "YOUR_API_KEY"
  reconnect_enabled: true
  reconnect_delay_ms: 5000

gameplay:
  debug_mode: false
  emotes_enabled: true
  message_prefix: "[NPC] "

npc:
  cache_directory: "data/npcs"
  sync_on_startup: true

logging:
  level: "INFO"
  log_chat: true
  log_actions: true
```

### pom.xml (Build)
Key sections:
- **Java 25** compiler settings with `--enable-preview`
- **Maven Shade Plugin** - Creates fat JAR for Hytale
- **Maven Assembly Plugin** - Creates JAR with dependencies for standalone
- **Sentry Maven Plugin** - Optional, requires `SENTRY_AUTH_TOKEN` env var

## Socket Events

### Plugin → Backend
| Event | Description |
|-------|-------------|
| `plugin:connect` | Authentication handshake |
| `plugin:chat` | Player chat to NPC |
| `plugin:request_sync` | Request NPC list |
| `plugin:npc_animations` | Report available animations |
| `plugin:blocks_available` | Report server blocks |

### Backend → Plugin
| Event | Description |
|-------|-------------|
| `backend:action` | MCP tool execution |
| `backend:npc_sync` | NPC create/update/delete |
| `backend:error` | Error notification |
| `backend:cot_update` | Chain-of-thought status |

## MCP Actions (Backend → Plugin)

The backend sends actions for the plugin to execute:

| Action | Params | Description |
|--------|--------|-------------|
| `say` | `{ message }` | NPC speaks to player |
| `emote` | `{ emotion }` | Play animation |
| `move_to` | `{ x, y, z }` | Move NPC to location |
| `follow_player` | `{ playerName }` | NPC follows player |
| `attack` | `{ target, type }` | NPC attacks target |
| `open_trade` | `{}` | Open trade interface |
| `give_quest` | `{ questId, questName }` | Offer quest |

## Testing

### Standalone Mode
Run without Hytale server:
```bash
java --enable-preview -jar target/hycompanion-plugin-1.1.0-SNAPSHOT-jar-with-dependencies.jar
```
Uses `MockHytaleAdapter` - simulates Hytale APIs with console output.

### Hytale Server Mode
Deploy to actual Hytale server:
1. Copy JAR to `plugins/` folder
2. Start server
3. Configure `plugins/Hycompanion/config.yml`

## Common Tasks

### Adding a New Command
1. Create inner class in `HycompanionCommand.java` extending `CommandBase` or `AbstractPlayerCommand`
2. Implement `execute()` or `executeSync()`
3. Register in constructor: `this.addSubCommand(new MyCommand())`
4. Add to `HelpCommand` and README

### Adding a New MCP Action
1. Add handler in `ActionExecutor.java`
2. Update `handleAction()` switch statement
3. Document in README.md Socket Events section
4. Coordinate with backend team (events.ts)

### Adding a New Socket Event
1. Add constant to `SocketEvents.java`
2. Register handler in `SocketManager.registerEventHandlers()`
3. Implement handler method (e.g., `onMyEvent()`)
4. Use Virtual Threads for non-blocking: `Thread.ofVirtual().start(() -> ...)`

### Handling Configuration Changes
1. Update `PluginConfig.java` record structure
2. Update `PluginConfig.load()` parsing logic
3. Update `config.yml` resource file
4. Update README documentation

## Security Considerations

- **Never commit secrets** - Use `.env` file (gitignored)
- **API keys** - Stored in config.yml at runtime, not in source
- **Sentry DSN** - Loaded from `SENTRY_DSN` env var, no hardcoded values
- **Player data** - Only send player ID/name to backend, no personal data

## External Dependencies

### Hytale Server API
- Provided at runtime by Hytale server
- Scope: `provided` in pom.xml
- Not included in JAR (shaded out)

### Backend API
- Socket.IO server at `api.hycompanion.dev` (production)
- Or self-hosted (MIT licensed)

## Troubleshooting Common Issues

### Build Failures
- **Java version**: Must be Java 25
- **Sentry upload fails**: Set `SENTRY_AUTH_TOKEN` or remove Sentry plugin from pom.xml

### Runtime Issues
- **Socket not connecting**: Check `api_key` in config.yml
- **NPCs not spawning**: Verify role files exist in `mods/<mod>/Server/NPC/Roles/`
- **"Invalid entity reference"**: Usually during shutdown, handled gracefully

### Development Issues
- **ClassNotFoundException**: Check Hytale Server API is in classpath
- **Thread violations**: Ensure world operations run on WorldThread

## Related Repositories

- **Backend**: https://github.com/hycompanion/hycompanion-backend (MIT)
- **Documentation**: https://hycompanion.dev
- **Dashboard**: https://app.hycompanion.dev

## Contact

- Website: https://hycompanion.dev
- Discord: https://discord.gg/hycompanion
- Sponsor: [VeryGames Hytale Hosting](https://www.verygames.com/en/product/prd-1920-hytale-server-rental?af=hycompanion)

---

*This guide helps AI agents understand the project context. For user documentation, see README.md.*
