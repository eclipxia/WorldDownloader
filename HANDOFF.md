# WorldDownloader – handoff

RusherHack plugin that saves the chunks the server sends you into a local singleplayer world (`.minecraft/saves/<name>/`).
Targets **26.2, 1.21.11 and 1.21.4** (mojmap, Fabric, RusherHack API `<mc>-SNAPSHOT`).

## Status (2026-11-04)
- All three versions **compile and build**. Nothing has been run in-game yet.
- `RegionWriter` was round-trip tested offline (write, rewrite larger, read back, zlib decompress).
- `ChunkSerializer` / `WorldSaver` / `level.dat` output are **untested** against a real client.
- Nothing is committed to git.

## Build
```
./gradlew -Pmc=1.21.4  build
./gradlew -Pmc=1.21.11 build
./gradlew -Pmc=26.2    build
```
Jars land in `build/libs/WorldDownloader-<ver>+<mc>.jar`. Install into `rusherhack/plugins/`, launch with
`-Drusherhack.enablePlugins=true`, use `*reload` after changes.

- `versions/<mc>.properties` holds per-version Java version, Parchment mappings, and an `unobfuscated` flag.
- 26.2 is unobfuscated, so it uses the `net.fabricmc.fabric-loom` plugin. The others use `fabric-loom-remap`.
- Loom 1.19.3 requires Gradle 9.8.0 (set in `gradle/wrapper/gradle-wrapper.properties`).
- `src/main` is shared code. `src/mc-<mc>/java` is added to the source set for the selected version.

## Layout (`src/main/java/org/worlddownloader`)
| File | Role |
|---|---|
| `WorldDownloaderPlugin` | Registers module and command |
| `DownloaderModule` | `ToggleableModule` (World). Settings: Name, BlockEntities, ResaveRadius. Subscribes to `EventChunk.Load/Unload` |
| `WdlCommand` | `*wdl` (status), `start`, `stop`, `resave` |
| `WorldSaver` | One session = one save folder. Serializes on the main thread, writes on a background thread, writes `level.dat` on finish |
| `ChunkSerializer` | LevelChunk to vanilla chunk NBT (palettes, packed longs, block entities) |
| `RegionWriter` | Own `.mca` writer with sector allocation, zlib |
| `Chunks` | `ChunkPos` x/z helper (fields are private on 26.2) |
| `McCompat` (per `src/mc-*`) | `dataVersion()` and `versionName()`, the only API that differs so far |

## Known gaps / next steps
1. **Test in-game on all three versions.** Likeliest failures are `BlockEntity.saveWithFullMetadata(registryAccess)` (may differ on 1.21.11 / 26.2), `level.dat` field names for newer versions, and `Holder.getRegisteredName()` for biomes.
2. Entities are not saved, and player inventory is not saved.
3. Lighting is not saved (`isLightOn=0`, the game relights on load).
4. Heightmaps are not written. Custom dimensions are skipped (only overworld, nether, end).
5. `level.dat` writes both `SpawnX/Y/Z` and the newer `spawn` compound. Verify the game accepts it on each version.
6. Re-save on unload relies on `getChunkNow` still returning the chunk. Verify this. `*wdl resave` is the manual fallback.
7. Chunks are serialized on the main thread (about 24 sections x 4096 blocks each), so profile for stutter when many chunks load at once.
8. No README, no CI, no release packaging. Optionally add a task that builds all three jars.

## API notes
- API sources are fetched from `https://maven.rusherhack.org/snapshots/org/rusherhack/rusherhack-api/<mc>-SNAPSHOT/` (`-sources.jar`).
- Events used: `org.rusherhack.client.api.events.world.EventChunk` (Load has `getChunk()`, Unload only has `getChunkPos()`). `EventLoadWorld` exists but is currently unused.
- Event subscriptions in a module are only active while the module is enabled.
