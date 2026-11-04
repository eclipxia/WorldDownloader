package org.worlddownloader;

import net.minecraft.world.level.ChunkPos;

/** ChunkPos.x/z are public fields on older versions but private on newer ones; this works everywhere. */
final class Chunks {
	private Chunks() {}
	
	static int x(ChunkPos p) {
		return p.getMinBlockX() >> 4;
	}
	
	static int z(ChunkPos p) {
		return p.getMinBlockZ() >> 4;
	}
}
