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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a client side LevelChunk into the vanilla chunk NBT format.
 * Must run on the main thread (reads live chunk data); the result is safe to hand to other threads.
 */
public final class ChunkSerializer {
	private ChunkSerializer() {}
	
	public static CompoundTag serialize(Level level, LevelChunk chunk, boolean blockEntities) {
		final CompoundTag root = new CompoundTag();
		root.putInt("DataVersion", McCompat.dataVersion());
		root.putInt("xPos", Chunks.x(chunk.getPos()));
		root.putInt("zPos", Chunks.z(chunk.getPos()));
		root.putInt("yPos", chunk.getMinSectionY());
		root.putString("Status", "minecraft:full");
		root.putLong("LastUpdate", level.getGameTime());
		root.putLong("InhabitedTime", 0L);
		root.putByte("isLightOn", (byte) 0); // let the game relight on load
		
		final ListTag sections = new ListTag();
		final LevelChunkSection[] arr = chunk.getSections();
		for(int i = 0; i < arr.length; i++) {
			final CompoundTag s = serializeSection(arr[i]);
			s.putByte("Y", (byte) (chunk.getMinSectionY() + i));
			sections.add(s);
		}
		root.put("sections", sections);
		
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
		root.put("block_entities", beList);
		return root;
	}
	
	private static CompoundTag serializeSection(LevelChunkSection section) {
		final CompoundTag out = new CompoundTag();
		
		//blocks: index = y << 8 | z << 4 | x
		final Map<BlockState, Integer> blockIds = new HashMap<>();
		final List<BlockState> blockPalette = new ArrayList<>();
		final int[] blocks = new int[4096];
		for(int y = 0; y < 16; y++) {
			for(int z = 0; z < 16; z++) {
				for(int x = 0; x < 16; x++) {
					final BlockState state = section.getBlockState(x, y, z);
					Integer id = blockIds.get(state);
					if(id == null) {
						id = blockPalette.size();
						blockIds.put(state, id);
						blockPalette.add(state);
					}
					blocks[(y << 8) | (z << 4) | x] = id;
				}
			}
		}
		final ListTag blockPaletteTag = new ListTag();
		for(BlockState state : blockPalette) {
			blockPaletteTag.add(NbtUtils.writeBlockState(state));
		}
		final CompoundTag blockStates = new CompoundTag();
		blockStates.put("palette", blockPaletteTag);
		if(blockPalette.size() > 1) {
			blockStates.put("data", new LongArrayTag(pack(blocks, Math.max(4, bitsFor(blockPalette.size())))));
		}
		out.put("block_states", blockStates);
		
		//biomes: 4x4x4 cells, index = y << 4 | z << 2 | x
		final Map<String, Integer> biomeIds = new HashMap<>();
		final List<String> biomePalette = new ArrayList<>();
		final int[] biomes = new int[64];
		for(int y = 0; y < 4; y++) {
			for(int z = 0; z < 4; z++) {
				for(int x = 0; x < 4; x++) {
					final Holder<Biome> holder = section.getNoiseBiome(x, y, z);
					final String name = holder.getRegisteredName();
					Integer id = biomeIds.get(name);
					if(id == null) {
						id = biomePalette.size();
						biomeIds.put(name, id);
						biomePalette.add(name);
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
