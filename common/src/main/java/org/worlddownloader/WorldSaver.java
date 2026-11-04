package org.worlddownloader;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * One download session = one world save folder.
 * Chunks are serialized on the calling (main) thread and written to disk on a background thread.
 */
public class WorldSaver {
	
	private final Path root;
	private final String levelName;
	private final boolean blockEntities;
	private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
		final Thread t = new Thread(r, "WorldDownloader-IO");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private final Map<Path, RegionWriter> writers = new HashMap<>();
	/** newest snapshot per chunk that has not been written yet; a newer snapshot replaces an older queued one */
	private final Map<ChunkKey, Job> pending = new LinkedHashMap<>();
	/** checksum of the last data written per chunk (IO thread only), used to skip unchanged chunks */
	private final Map<ChunkKey, Long> lastWritten = new HashMap<>();
	
	private record ChunkKey(String dim, int x, int z) {}
	
	private record Job(Path regionFile, ChunkSerializer.Snapshot snapshot) {}
	private final AtomicInteger saved = new AtomicInteger();
	private final AtomicInteger failed = new AtomicInteger();
	
	public WorldSaver(Path root, String levelName, boolean blockEntities) {
		this.root = root;
		this.levelName = levelName;
		this.blockEntities = blockEntities;
	}
	
	public Path getRoot() {
		return this.root;
	}
	
	public int getSavedCount() {
		return this.saved.get();
	}
	
	public int getFailedCount() {
		return this.failed.get();
	}
	
	/** @return folder of the dimension relative to the world root, or null if unsupported */
	private static String dimensionFolder(Level level) {
		final boolean modern = McCompat.modernSaveFormat();
		if(level.dimension() == Level.OVERWORLD) return modern ? "dimensions/minecraft/overworld" : "";
		if(level.dimension() == Level.NETHER) return modern ? "dimensions/minecraft/the_nether" : "DIM-1";
		if(level.dimension() == Level.END) return modern ? "dimensions/minecraft/the_end" : "DIM1";
		return null;
	}
	
	/** Main thread only. Only takes a cheap snapshot; serialization, compression and disk IO happen on the IO thread. */
	public void save(Level level, LevelChunk chunk) {
		final String dim = dimensionFolder(level);
		if(dim == null) {
			return;
		}
		final ChunkPos pos = chunk.getPos();
		final ChunkSerializer.Snapshot snapshot;
		try {
			snapshot = ChunkSerializer.snapshot(level, chunk, this.blockEntities);
		} catch(Exception e) {
			this.failed.incrementAndGet();
			return;
		}
		
		final Path regionFile = this.root.resolve(dim).resolve("region").resolve("r." + (Chunks.x(pos) >> 5) + "." + (Chunks.z(pos) >> 5) + ".mca");
		final ChunkKey key = new ChunkKey(dim, Chunks.x(pos), Chunks.z(pos));
		final boolean queued;
		synchronized(this.pending) {
			queued = this.pending.put(key, new Job(regionFile, snapshot)) == null;
		}
		if(queued) {
			//exactly one task per pending entry; a replaced entry is already covered by its existing task
			this.io.execute(this::processNext);
		}
	}
	
	/** IO thread. */
	private void processNext() {
		final ChunkKey key;
		final Job job;
		synchronized(this.pending) {
			final var it = this.pending.entrySet().iterator();
			if(!it.hasNext()) {
				return;
			}
			final var entry = it.next();
			key = entry.getKey();
			job = entry.getValue();
			it.remove();
		}
		try {
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream(32 * 1024);
			try(DataOutputStream out = new DataOutputStream(bytes)) {
				NbtIo.write(ChunkSerializer.serialize(job.snapshot()), out);
			}
			final byte[] raw = bytes.toByteArray();
			final CRC32 crc = new CRC32();
			crc.update(raw);
			final Long previous = this.lastWritten.get(key);
			if(previous != null && previous == crc.getValue()) {
				return; //unchanged since it was last written
			}
			final byte[] payload = RegionWriter.compress(raw);
			
			RegionWriter writer = this.writers.get(job.regionFile());
			if(writer == null) {
				writer = new RegionWriter(job.regionFile());
				this.writers.put(job.regionFile(), writer);
			}
			writer.write(key.x() & 31, key.z() & 31, payload);
			this.lastWritten.put(key, crc.getValue());
			this.saved.incrementAndGet();
		} catch(Exception e) {
			this.failed.incrementAndGet();
		}
	}
	
	/** Writes level.dat, flushes and closes all files. Blocks briefly while pending writes drain. */
	public void finish(BlockPos spawn) {
		this.io.execute(() -> {
			for(RegionWriter w : this.writers.values()) {
				try {
					w.close();
				} catch(IOException ignored) {}
			}
			this.writers.clear();
			try {
				this.writeLevelDat(spawn);
			} catch(IOException e) {
				this.failed.incrementAndGet();
			}
		});
		this.io.shutdown();
		try {
			this.io.awaitTermination(30, TimeUnit.SECONDS);
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
	
	private void writeLevelDat(BlockPos spawn) throws IOException {
		final CompoundTag version = new CompoundTag();
		version.putInt("Id", McCompat.dataVersion());
		version.putString("Name", McCompat.versionName());
		version.putBoolean("Snapshot", false);
		version.putString("Series", "main");
		
		final CompoundTag data = new CompoundTag();
		data.putInt("DataVersion", McCompat.dataVersion());
		data.putInt("version", 19133);
		data.put("Version", version);
		data.putString("LevelName", this.levelName);
		data.putInt("GameType", 1);
		data.putBoolean("allowCommands", true);
		data.putBoolean("initialized", true);
		data.putLong("LastPlayed", System.currentTimeMillis());
		
		if(McCompat.modernSaveFormat()) {
			final CompoundTag difficulty = new CompoundTag();
			difficulty.putString("difficulty", "normal");
			difficulty.putBoolean("hardcore", false);
			difficulty.putBoolean("locked", false);
			data.put("difficulty_settings", difficulty);
		} else {
			data.putBoolean("hardcore", false);
			data.putByte("Difficulty", (byte) 2);
			data.putInt("SpawnX", spawn.getX());
			data.putInt("SpawnY", spawn.getY());
			data.putInt("SpawnZ", spawn.getZ());
		}
		
		//spawn compound is used by 1.21.9 and newer
		final CompoundTag spawnTag = new CompoundTag();
		spawnTag.putIntArray("pos", new int[]{spawn.getX(), spawn.getY(), spawn.getZ()});
		spawnTag.putString("dimension", "minecraft:overworld");
		spawnTag.putFloat("yaw", 0f);
		spawnTag.putFloat("pitch", 0f);
		data.put("spawn", spawnTag);
		
		final CompoundTag root = new CompoundTag();
		root.put("Data", data);
		Files.createDirectories(this.root);
		NbtIo.writeCompressed(root, this.root.resolve("level.dat"));
		if(McCompat.modernSaveFormat()) {
			this.writeWorldGenSettings();
		}
	}
	
	/** 26.x stores the world generation settings in data/minecraft/world_gen_settings.dat instead of level.dat. */
	private void writeWorldGenSettings() throws IOException {
		final CompoundTag dimensions = new CompoundTag();
		dimensions.put("minecraft:overworld", dimension("minecraft:overworld", "minecraft:overworld", "minecraft:multi_noise", "minecraft:overworld"));
		dimensions.put("minecraft:the_nether", dimension("minecraft:the_nether", "minecraft:nether", "minecraft:multi_noise", "minecraft:nether"));
		dimensions.put("minecraft:the_end", dimension("minecraft:the_end", "minecraft:end", "minecraft:the_end", null));
		
		final CompoundTag data = new CompoundTag();
		data.putByte("bonus_chest", (byte) 0);
		data.putLong("seed", 0L);
		data.putByte("generate_structures", (byte) 1);
		data.put("dimensions", dimensions);
		
		final CompoundTag file = new CompoundTag();
		file.put("data", data);
		file.putInt("DataVersion", McCompat.dataVersion());
		
		final Path dir = this.root.resolve("data").resolve("minecraft");
		Files.createDirectories(dir);
		NbtIo.writeCompressed(file, dir.resolve("world_gen_settings.dat"));
	}
	
	private static CompoundTag dimension(String type, String settings, String biomeSourceType, String preset) {
		final CompoundTag biomeSource = new CompoundTag();
		if(preset != null) biomeSource.putString("preset", preset);
		biomeSource.putString("type", biomeSourceType);
		final CompoundTag generator = new CompoundTag();
		generator.putString("settings", settings);
		generator.put("biome_source", biomeSource);
		generator.putString("type", "minecraft:noise");
		final CompoundTag dim = new CompoundTag();
		dim.put("generator", generator);
		dim.putString("type", type);
		return dim;
	}
}
