# WorldDownloader

A [RusherHack](https://rusherhack.org) plugin that saves the chunks a server sends you into a local singleplayer world (`.minecraft/saves/<name>/`).

> **Status:** compiles on all supported versions, but has not been tested in-game yet. See [HANDOFF.md](HANDOFF.md) for known gaps.

## Features

The `WorldDownloader` module (category: World) saves loaded chunks to a vanilla save folder, including block entities. Chunks are serialized on the main thread and written on a background thread.

| Setting         | Description                                         |
|-----------------|-----------------------------------------------------|
| `Name`          | Name of the save folder.                            |
| `BlockEntities` | Also save block entities (chests, signs, ...).      |
| `ResaveRadius`  | Radius used when re-saving chunks.                  |

### Command

| Command        | Description                |
|----------------|----------------------------|
| `*wdl`         | Show status.               |
| `*wdl start`   | Start a save session.      |
| `*wdl stop`    | Stop and write `level.dat`.|
| `*wdl resave`  | Re-save nearby chunks.     |

Not saved yet: entities, player inventory, lighting, heightmaps, custom dimensions.

## Supported versions

| Minecraft | Module     | Mappings            |
|-----------|------------|---------------------|
| 1.21.4    | `1.21.4/`  | Mojmap + Parchment  |
| 1.21.11   | `1.21.11/` | Mojmap + Parchment  |
| 26.2      | `26.2/`    | none (unobfuscated) |

Each version is built as its own jar: `WorldDownloader-<minecraft version>-<plugin version>.jar`.

## Installing

1. Build the jar for your Minecraft version (see below).
2. Drop it into your RusherHack plugins folder (`.minecraft/rusherhack/plugins`).
3. Start the game with `-Drusherhack.enablePlugins=true` and enable `WorldDownloader` in the RusherHack GUI.

## Project layout

Multi-project Gradle build.

```
common/        shared plugin code and resources, compiled into every version
<version>/     one folder per Minecraft version
  build.gradle, gradle.properties
  src/main/java/org/worlddownloader/McCompat.java   per-version API differences
```

To support a new Minecraft version: copy the closest module folder, update its `gradle.properties`, add `include '<version>'` to `settings.gradle`, and fix what no longer compiles in `McCompat`. The RusherHack API `<version>-SNAPSHOT` must exist on the [RusherHack Maven](https://maven.rusherhack.org/snapshots).

## Building

```sh
./gradlew build                  # every version
./gradlew :1.21.11:build         # one version
```

Jars end up in `<version>/build/libs/`. Run `./gradlew :<version>:updateAccessWidener` the first time to fetch the RusherHack access widener.

## Running in a dev client

Copy `rusherhack-loader.jar` into `<version>/lib/` (gitignored), then `./gradlew :1.21.11:runPlugin`.

## Documentation

- [docs/architecture.md](docs/architecture.md): data flow, classes, threading and performance design
- [docs/save-format.md](docs/save-format.md): what is written, and how 1.21.x and 26.x differ
- [docs/development.md](docs/development.md): building, dev client, testing, adding a version
- [HANDOFF.md](HANDOFF.md): current status and known gaps

## License

See [LICENSE](LICENSE).
