# Architecture

WorldDownloader saves the chunks the server sends you into a vanilla singleplayer world in `.minecraft/saves/<name>/`.
Shared code is in `common/src/main/java/org/worlddownloader`; per-version shims are in `<mc>/src/main/java`.

## Data flow

```
EventChunk.Load / Unload, *wdl resave
        |
DownloaderModule              (main thread)  decides what to save
        |  WorldSaver.save(level, chunk)
WorldSaver.save               (main thread)  ChunkSerializer.snapshot(): copy palette containers + block entities
        |  pending map (one entry per chunk, newest wins)
WorldSaver.processNext        (IO thread)    ChunkSerializer.serialize() -> NBT -> CRC check -> zlib -> RegionWriter
        |
RegionWriter                                 .mca file, sector allocation
```

On disable (`*wdl stop`), `WorldSaver.finish()` queues the region file close and `level.dat` write behind all pending
chunks on the same IO thread, then waits up to 30 s for the queue to drain.

## Classes

| Class | Thread | Role |
|---|---|---|
| `WorldDownloaderPlugin` | main | Registers the module and the command |
| `DownloaderModule` | main | `ToggleableModule` (category World). Settings: `Name`, `BlockEntities`, `ResaveRadius`. Creates the `WorldSaver`, subscribes to chunk events (only active while enabled) |
| `WdlCommand` | main | `*wdl` status, `start`, `stop`, `resave` |
| `WorldSaver` | main + IO | One session = one save folder. Owns the IO thread, the pending map, the region writers, and writes `level.dat` (and `world_gen_settings.dat` on 26.x) |
| `ChunkSerializer` | `snapshot` main, `serialize` any | `LevelChunk` to vanilla chunk NBT |
| `RegionWriter` | IO | Own `.mca` writer; first-fit sector allocation; `compress()` is zlib level 2 |
| `Chunks` | any | `ChunkPos` x/z helper (the fields are private on newer versions) |
| `McCompat` | any | Per-version: `dataVersion()`, `versionName()`, `modernSaveFormat()` |

## Performance design

Anything that scales with block count must stay off the main thread.

- The main thread only calls `PalettedContainer.copy()` per section and serializes block entities (they read live state).
  Sections that are only air skip the copy and the block scan.
- Palette building, NBT, CRC, zlib and file writes run on the single `WorldDownloader-IO` daemon thread at minimum priority.
- `WorldSaver.pending` holds the newest snapshot per chunk. If a chunk is queued again before it is written, the old
  snapshot is replaced. There is exactly one queued task per pending entry.
- `lastWritten` (IO thread only) stores a CRC of each chunk's raw NBT, and unchanged chunks are not compressed or rewritten.
  This makes the extra save on chunk unload cheap.
- zlib level 2 trades slightly larger region files for much lower CPU.

When changing this code, keep new per-block work inside `ChunkSerializer.serialize`, not in `snapshot`.

## Threading rules

- `ChunkSerializer.snapshot` and `WorldSaver.save`: main thread only.
- `WorldSaver.processNext`, `writers`, `lastWritten`: IO thread only (no locking needed).
- `WorldSaver.pending` is guarded by `synchronized(pending)`.
- `RegionWriter` methods are `synchronized`.
