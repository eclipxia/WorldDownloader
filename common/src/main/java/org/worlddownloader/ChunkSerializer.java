package org.worlddownloader;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a client side LevelChunk into the vanilla chunk NBT format in two steps:
 * {@link #snapshot} copies the live data and must run on the main thread (it is cheap),
 * {@link #serialize} does the expensive palette/NBT work and is safe to run on any thread.
 */
public final class ChunkSerializer {
	private ChunkSerializer() {}
	
	/** Immutable copy of everything needed to serialize a chunk. */
	public static final class Snapshot {
		private final int dataVersion, x, z, minSectionY;
		private final long gameTime;
		/** per section; null entry = section only contains air */
		private final List<PalettedContainer<BlockState>> states;
		private final List<PalettedContainer<Holder<Biome>>> biomes;
		private final ListTag blockEntities;
		
		private Snapshot(int dataVersion, int x, int z, int minSectionY, long gameTime,
		                 List<PalettedContainer<BlockState>> states, List<PalettedContainer<Holder<Biome>>> biomes, ListTag blockEntities) {
			this.dataVersion = dataVersion;
			this.x = x;
			this.z = z;
			this.minSectionY = minSectionY;
			this.gameTime = gameTime;
			this.states = states;
			this.biomes = biomes;
			this.blockEntities = blockEntities;
		}
	}
	
	/** Main thread only. Copies the palette storage (no per block work). */
	public static Snapshot snapshot(Level level, LevelChunk chunk, boolean blockEntities) {
		final LevelChunkSection[] arr = chunk.getSections();
		final List<PalettedContainer<BlockState>> states = new ArrayList<>(arr.length);
		final List<PalettedContainer<Holder<Biome>>> biomes = new ArrayList<>(arr.length);
		for(LevelChunkSection section : arr) {
			states.add(section.hasOnlyAir() ? null : section.getStates().copy());
			biomes.add(section.getBiomes().copy());
		}
		
		final ListTag beList = new ListTag();
		if(blockEntities) {
			for(Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
				try {
					beList.add(e.getValue().saveWithFullMetadata(level.registryAccess()));
				} catch(Exception ex) {
					//a broken block entity must not stop the chunk from saving
				}
			}
		}
		return new Snapshot(McCompat.dataVersion(), Chunks.x(chunk.getPos()), Chunks.z(chunk.getPos()),
				chunk.getMinSectionY(), level.getGameTime(), states, biomes, beList);
	}
	
	/** Any thread. */
	public static CompoundTag serialize(Snapshot snap) {
		final CompoundTag root = new CompoundTag();
		root.putInt("DataVersion", snap.dataVersion);
		root.putInt("xPos", snap.x);
		root.putInt("zPos", snap.z);
		root.putInt("yPos", snap.minSectionY);
		root.putString("Status", "minecraft:full");
		root.putLong("LastUpdate", snap.gameTime);
		root.putLong("InhabitedTime", 0L);
		root.putByte("isLightOn", (byte) 0); // let the game relight on load
		
		final ListTag sections = new ListTag();
		for(int i = 0; i < snap.states.size(); i++) {
			final CompoundTag s = serializeSection(snap.states.get(i), snap.biomes.get(i));
			s.putByte("Y", (byte) (snap.minSectionY + i));
			sections.add(s);
		}
		root.put("sections", sections);
		root.put("block_entities", snap.blockEntities);
		return root;
	}
	
	private static CompoundTag serializeSection(PalettedContainer<BlockState> states, PalettedContainer<Holder<Biome>> biomeContainer) {
		final CompoundTag out = new CompoundTag();
		
		//blocks: index = y << 8 | z << 4 | x
		final CompoundTag blockStates = new CompoundTag();
		final ListTag blockPaletteTag = new ListTag();
		if(states == null) {
			blockPaletteTag.add(NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()));
		} else {
			final Map<BlockState, Integer> blockIds = new IdentityHashMap<>();
			final List<BlockState> blockPalette = new ArrayList<>();
			final int[] blocks = new int[4096];
			BlockState last = null;
			int lastId = 0;
			for(int y = 0; y < 16; y++) {
				for(int z = 0; z < 16; z++) {
					for(int x = 0; x < 16; x++) {
						final BlockState state = states.get(x, y, z);
						if(state != last) {
							Integer id = blockIds.get(state);
							if(id == null) {
								id = blockPalette.size();
								blockIds.put(state, id);
								blockPalette.add(state);
							}
							last = state;
							lastId = id;
						}
						blocks[(y << 8) | (z << 4) | x] = lastId;
					}
				}
			}
			for(BlockState state : blockPalette) {
				blockPaletteTag.add(NbtUtils.writeBlockState(state));
			}
			if(blockPalette.size() > 1) {
				blockStates.put("data", new LongArrayTag(pack(blocks, Math.max(4, bitsFor(blockPalette.size())))));
			}
		}
		blockStates.put("palette", blockPaletteTag);
		out.put("block_states", blockStates);
		
		//biomes: 4x4x4 cells, index = y << 4 | z << 2 | x
		final Map<Holder<Biome>, Integer> biomeIds = new IdentityHashMap<>();
		final List<String> biomePalette = new ArrayList<>();
		final int[] biomes = new int[64];
		for(int y = 0; y < 4; y++) {
			for(int z = 0; z < 4; z++) {
				for(int x = 0; x < 4; x++) {
					final Holder<Biome> holder = biomeContainer.get(x, y, z);
					Integer id = biomeIds.get(holder);
					if(id == null) {
						id = biomePalette.size();
						biomeIds.put(holder, id);
						biomePalette.add(holder.getRegisteredName());
					}
					biomes[(y << 4) | (z << 2) | x] = id;
				}
			}
		}
		final ListTag biomePaletteTag = new ListTag();
		for(String name : biomePalette) {
			biomePaletteTag.add(StringTag.valueOf(name));
		}
		final CompoundTag biomeTag = new CompoundTag();
		biomeTag.put("palette", biomePaletteTag);
		if(biomePalette.size() > 1) {
			biomeTag.put("data", new LongArrayTag(pack(biomes, Math.max(1, bitsFor(biomePalette.size())))));
		}
		out.put("biomes", biomeTag);
		return out;
	}
	
	private static int bitsFor(int paletteSize) {
		return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
	}
	
	/** Packs values into longs without spanning (format used since 1.16). */
	private static long[] pack(int[] values, int bits) {
		final int perLong = 64 / bits;
		final long[] out = new long[(values.length + perLong - 1) / perLong];
		final long mask = (1L << bits) - 1L;
		for(int i = 0; i < values.length; i++) {
			out[i / perLong] |= ((long) values[i] & mask) << ((i % perLong) * bits);
		}
		return out;
	}
}
