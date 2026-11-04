# Development

## Requirements

JDK 25 on the machine (Gradle toolchains pick 21 or 25 per module from `<mc>/gradle.properties`). The Gradle wrapper is included.

## Build

```sh
./gradlew build                  # every version
./gradlew :26.2:build            # one version
```

Jars: `<mc>/build/libs/WorldDownloader-<mc>-<version>.jar`.

## Run in a dev client

1. `rusherhack-loader.jar` goes in `<mc>/lib/` (gitignored).
2. `./gradlew :<mc>:updateAccessWidener` (first time and after changing the Minecraft version).
3. `./gradlew :<mc>:runPlugin`. Add `-PquickPlay="<world>"` to join a singleplayer world directly.

The dev client uses `<mc>/run/` as its game directory. Saved worlds end up in `<mc>/run/saves/`. Logs: `<mc>/run/logs/latest.log`,
crash reports in `<mc>/run/crash-reports/`.

Known issue: RusherHack 2.0.8 moved the plugin jar from `run/rusherhack/plugins/` into `run/rusherhack/legacy/plugins/` on
first start. `copyPluginToRunDir` still targets the old folder, so check that the plugin actually loads.

A "crash" with `Watchdog (Client shutdown from post-main)` just means the game was closed and shutdown was slow.

## Testing a change

There are no automated tests. After changing the save code:

1. Run the dev client, join a server, `*wdl start`, walk around, `*wdl stop`.
2. Open the saved world and check blocks, biomes and block entities.
3. Check `latest.log` for errors from the plugin.

`RegionWriter` can be tested offline: write, rewrite larger, read back, decompress.

## Version differences

Keep `common/` identical across versions and put anything that differs into `McCompat` in the version module
(`<mc>/src/main/java/org/worlddownloader/McCompat.java`). Both the obfuscated (Mojmap + Parchment, `fabric-loom`) and the
unobfuscated (26.x) modules compile the same `common/` sources.

## Adding a Minecraft version

1. Copy the closest `<mc>/` folder (use a 26.x folder for unobfuscated versions, otherwise a 1.21.x one).
2. Edit its `gradle.properties`: `minecraft_version`, `java_version`, `parchment_version` (obfuscated only), `fabric_loader_version`.
3. Add `include '<mc>'` to `settings.gradle`.
4. Fix what no longer compiles in `McCompat`, and check the save layout (see [save-format.md](save-format.md)).
5. The RusherHack API `<mc>-SNAPSHOT` must exist on https://maven.rusherhack.org/snapshots. API sources are in the `-sources.jar` there.

## RusherHack API notes

- Events used: `org.rusherhack.client.api.events.world.EventChunk`. `Load` has `getChunk()`, `Unload` only has `getChunkPos()`.
  `EventLoadWorld` exists but is unused.
- Event subscriptions in a module are only active while the module is enabled.

## Versioning

The plugin version is `version` in the root `gradle.properties`. Tools such as the shell prompt read it from there, so don't move it into an expression in `build.gradle`.

## Git

Commit every change in small commits with clear messages. History is not rewritten.
