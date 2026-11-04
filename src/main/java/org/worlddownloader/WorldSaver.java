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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
		return t;
	});
	private final Map<Path, RegionWriter> writers = new HashMap<>();
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
		if(level.dimension() == Level.OVERWORLD) return "";
		if(level.dimension() == Level.NETHER) return "DIM-1";
		if(level.dimension() == Level.END) return "DIM1";
		return null;
	}
	
	/** Main thread only. */
	public void save(Level level, LevelChunk chunk) {
		final String dim = dimensionFolder(level);
		if(dim == null) {
			return;
		}
		final ChunkPos pos = chunk.getPos();
		final byte[] payload;
		try {
			final CompoundTag tag = ChunkSerializer.serialize(level, chunk, this.blockEntities);
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream(32 * 1024);
			try(DataOutputStream out = new DataOutputStream(bytes)) {
				NbtIo.write(tag, out);
			}
			payload = RegionWriter.compress(bytes.toByteArray());
		} catch(Exception e) {
			this.failed.incrementAndGet();
			return;
		}
		
		final Path regionFile = this.root.resolve(dim).resolve("region").resolve("r." + (Chunks.x(pos) >> 5) + "." + (Chunks.z(pos) >> 5) + ".mca");
		this.io.execute(() -> {
			try {
				RegionWriter writer = this.writers.get(regionFile);
				if(writer == null) {
					writer = new RegionWriter(regionFile);
					this.writers.put(regionFile, writer);
				}
				writer.write(Chunks.x(pos) & 31, Chunks.z(pos) & 31, payload);
				this.saved.incrementAndGet();
			} catch(IOException e) {
				this.failed.incrementAndGet();
			}
		});
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
		data.putBoolean("hardcore", false);
		data.putBoolean("initialized", true);
		data.putLong("LastPlayed", System.currentTimeMillis());
		data.putByte("Difficulty", (byte) 2);
		
		//spawn: pre 1.21.9 uses SpawnX/Y/Z, newer versions use a "spawn" compound. Extra tags are ignored.
		data.putInt("SpawnX", spawn.getX());
		data.putInt("SpawnY", spawn.getY());
		data.putInt("SpawnZ", spawn.getZ());
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
	}
}
