# Save format

What the plugin writes, and how it differs per Minecraft version. The `McCompat.modernSaveFormat()` flag selects the
26.x layout (true on 26.2 only).

## Layout

| | 1.21.4, 1.21.11 | 26.x |
|---|---|---|
| Overworld regions | `region/` | `dimensions/minecraft/overworld/region/` |
| Nether regions | `DIM-1/region/` | `dimensions/minecraft/the_nether/region/` |
| End regions | `DIM1/region/` | `dimensions/minecraft/the_end/region/` |
| World generation settings | not written (see below) | `data/minecraft/world_gen_settings.dat` |
| Difficulty in `level.dat` | `Difficulty` (byte) + `hardcore` | `difficulty_settings` compound (`difficulty`, `hardcore`, `locked`) |
| Spawn in `level.dat` | `SpawnX/Y/Z` | `spawn` compound (`pos`, `dimension`, `yaw`, `pitch`) |

Custom dimensions are skipped.

## Verified status

- **26.2:** a saved world opens in-game. Without the 26.x layout the game fails with `Overworld settings missing`.
- **1.21.4 / 1.21.11:** not run in-game. They probably need a `WorldGenSettings` compound in `level.dat`.

## level.dat

Always written: `DataVersion`, `version` (19133), `Version` {Id, Name, Snapshot, Series}, `LevelName`, `GameType` (1, creative),
`allowCommands`, `initialized`, `LastPlayed`, plus the version-specific tags above. Spawn is the player's position when the
module is disabled.

## world_gen_settings.dat (26.x)

Seed `0`, `generate_structures` 1, `bonus_chest` 0, and the default overworld/nether/end generators, copied from a vanilla
26.2 save. Terrain outside the downloaded area therefore generates from seed 0, not from the server's seed.

## Chunk NBT

Written by `ChunkSerializer`: `DataVersion`, `xPos`, `zPos`, `yPos`, `Status` = `minecraft:full`, `LastUpdate`, `InhabitedTime`,
`isLightOn` = 0 (the game relights on load), `sections` (`block_states` and `biomes` palettes with packed longs, no spanning
since 1.16), and `block_entities`.

Not written: entities, heightmaps, lighting, player data/inventory.

## Region files

Standard Anvil `.mca`: two 4 KiB header sectors (offsets, timestamps), chunk data in whole sectors, zlib (type 2).
`RegionWriter` reopens existing files and keeps their allocations. A chunk over 255 sectors is rejected.

## How to find out what a new version expects

Open a vanilla world made by the same client and diff it against ours. `~/git/master/tools/tnbt.py` is a small NBT
reader/writer that can dump `level.dat` and the files under `data/`. This is how the 26.x differences were found.
