package org.worlddownloader;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;

/**
 * Minimal Anvil (.mca) region file writer. Chunks are stored zlib compressed.
 * Version independent: the on-disk region format has not changed across the supported versions.
 */
public class RegionWriter implements Closeable {
	
	private static final int SECTOR = 4096;
	
	private final RandomAccessFile file;
	private final int[] offsets = new int[1024]; // (sectorOffset << 8) | sectorCount
	private final int[] timestamps = new int[1024];
	private final BitSet used = new BitSet();
	
	public RegionWriter(Path path) throws IOException {
		Files.createDirectories(path.getParent());
		this.file = new RandomAccessFile(path.toFile(), "rw");
		
		if(this.file.length() < SECTOR * 2L) {
			this.file.setLength(SECTOR * 2L);
		}
		this.used.set(0, 2);
		
		final ByteBuffer header = ByteBuffer.allocate(SECTOR * 2);
		this.file.seek(0);
		this.file.readFully(header.array());
		for(int i = 0; i < 1024; i++) {
			this.offsets[i] = header.getInt(i * 4);
			this.timestamps[i] = header.getInt(SECTOR + i * 4);
			final int start = this.offsets[i] >> 8, count = this.offsets[i] & 0xFF;
			if(start >= 2 && count > 0) {
				this.used.set(start, start + count);
			}
		}
	}
	
	/**
	 * @param payload already zlib compressed NBT
	 */
	public synchronized void write(int localX, int localZ, byte[] payload) throws IOException {
		final int idx = (localX & 31) + (localZ & 31) * 32;
		
		final int length = payload.length + 1; // + compression type byte
		final int total = length + 4;
		final int sectors = (total + SECTOR - 1) / SECTOR;
		if(sectors > 255) {
			throw new IOException("Chunk too large for region file: " + total + " bytes");
		}
		
		//free the old allocation, then first-fit a new one
		final int oldStart = this.offsets[idx] >> 8, oldCount = this.offsets[idx] & 0xFF;
		if(oldStart >= 2 && oldCount > 0) {
			this.used.clear(oldStart, oldStart + oldCount);
		}
		int start = 2;
		while(true) {
			start = this.used.nextClearBit(start);
			final int nextUsed = this.used.nextSetBit(start);
			if(nextUsed == -1 || nextUsed - start >= sectors) {
				break;
			}
			start = nextUsed;
		}
		this.used.set(start, start + sectors);
		
		final ByteBuffer buf = ByteBuffer.allocate(sectors * SECTOR);
		buf.putInt(length);
		buf.put((byte) 2); // zlib
		buf.put(payload);
		
		if(this.file.length() < (long) (start + sectors) * SECTOR) {
			this.file.setLength((long) (start + sectors) * SECTOR);
		}
		this.file.seek((long) start * SECTOR);
		this.file.write(buf.array());
		
		this.offsets[idx] = (start << 8) | sectors;
		this.timestamps[idx] = (int) (System.currentTimeMillis() / 1000L);
		
		final ByteBuffer entry = ByteBuffer.allocate(4);
		this.file.seek(idx * 4L);
		this.file.write(entry.putInt(0, this.offsets[idx]).array());
		this.file.seek(SECTOR + idx * 4L);
		this.file.write(entry.putInt(0, this.timestamps[idx]).array());
	}
	
	@Override
	public synchronized void close() throws IOException {
		this.file.close();
	}
	
	public static byte[] compress(byte[] raw) throws IOException {
		final ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2 + 64);
		try(var def = new java.util.zip.DeflaterOutputStream(out)) {
			def.write(raw);
		}
		return out.toByteArray();
	}
}
