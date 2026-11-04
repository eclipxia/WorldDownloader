# WorldDownloader – handoff

Detailed docs are in `docs/` (architecture, save format, development).

RusherHack plugin that saves the chunks the server sends you into a local singleplayer world (`.minecraft/saves/<name>/`).
Targets **26.2, 1.21.11 and 1.21.4** (mojmap, Fabric, RusherHack API `<mc>-SNAPSHOT`).

## Status (2026-11-04)
- All three versions **compile and build**.
- **26.2:** a downloaded world saves and opens in-game (after switching to the 26.x save format: `dimensions/minecraft/<dim>/`, `data/minecraft/world_gen_settings.dat`, `difficulty_settings` in `level.dat`). Chunk contents not yet inspected in detail.
- **1.21.4 / 1.21.11:** not run in-game. They may need a `WorldGenSettings` compound in `level.dat`.
- `RegionWriter` was round-trip tested offline (write, rewrite larger, read back, zlib decompress).

## Build
Multi-project Gradle build, same layout as `~/git/master` (auto-raid): `common/` plus one subproject per Minecraft version.
```
./gradlew build                 # every version
./gradlew :1.21.4:build         # one version (also :1.21.11, :26.2)
```
Jars land in `<mc>/build/libs/WorldDownloader-<mc>-<ver>.jar`. Install into `rusherhack/plugins/`, launch with
`-Drusherhack.enablePlugins=true`, use `*reload` after changes.

- Root `gradle.properties` holds `plugin_name`, `version`, `maven_group`. `<mc>/gradle.properties` holds Java version, Parchment mappings, loader version.
- 26.2 is unobfuscated (no mappings, no remap). The others use Mojmap + Parchment.
- Loom 1.19.3 requires Gradle 9.8.0 (set in `gradle/wrapper/gradle-wrapper.properties`).
- `common/src/main` is shared code and resources, compiled into every version. `<mc>/src/main/java` holds the per-version shims.
- `*:updateAccessWidener` fetches the RusherHack access widener; `:<mc>:runPlugin` starts a dev client (needs `<mc>/lib/rusherhack-loader.jar`, gitignored).

## Layout (`common/src/main/java/org/worlddownloader`)
| File | Role |
|---|---|
| `WorldDownloaderPlugin` | Registers module and command |
| `DownloaderModule` | `ToggleableModule` (World). Settings: Name, BlockEntities, ResaveRadius. Subscribes to `EventChunk.Load/Unload` |
| `WdlCommand` | `*wdl` (status), `start`, `stop`, `resave` |
| `WorldSaver` | One session = one save folder. Serializes on the main thread, writes on a background thread, writes `level.dat` on finish |
| `ChunkSerializer` | LevelChunk to vanilla chunk NBT (palettes, packed longs, block entities) |
| `RegionWriter` | Own `.mca` writer with sector allocation, zlib |
| `Chunks` | `ChunkPos` x/z helper (fields are private on 26.2) |
| `McCompat` (per `<mc>/src/main/java`) | `dataVersion()` and `versionName()`, the only API that differs so far |

## Known gaps / next steps
1. **Test in-game on all three versions.** Likeliest failures are `BlockEntity.saveWithFullMetadata(registryAccess)` (may differ on 1.21.11 / 26.2), `level.dat` field names for newer versions, and `Holder.getRegisteredName()` for biomes.
2. Entities are not saved, and player inventory is not saved.
3. Lighting is not saved (`isLightOn=0`, the game relights on load).
4. Heightmaps are not written. Custom dimensions are skipped (only overworld, nether, end).
5. `level.dat` writes both `SpawnX/Y/Z` and the newer `spawn` compound. Verify the game accepts it on each version.
6. Re-save on unload relies on `getChunkNow` still returning the chunk. Verify this. `*wdl resave` is the manual fallback.
7. Performance: the main thread only snapshots (copies palette containers, serializes block entities). Palette/NBT work, zlib (level 2) and disk IO run on a low priority IO thread. Queued snapshots are coalesced per chunk and unchanged chunks (CRC of the NBT) are not rewritten. Not yet profiled in-game.
8. No README, no CI, no release packaging. Optionally add a task that builds all three jars.

## API notes
- API sources are fetched from `https://maven.rusherhack.org/snapshots/org/rusherhack/rusherhack-api/<mc>-SNAPSHOT/` (`-sources.jar`).
- Events used: `org.rusherhack.client.api.events.world.EventChunk` (Load has `getChunk()`, Unload only has `getChunkPos()`). `EventLoadWorld` exists but is currently unused.
- Event subscriptions in a module are only active while the module is enabled.
