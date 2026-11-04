package org.worlddownloader;

import net.minecraft.SharedConstants;

/** Version specific Minecraft accessors. Same API in every src/mc-* dir. */
public final class McCompat {
	private McCompat() {}
	
	public static int dataVersion() {
		return SharedConstants.getCurrentVersion().dataVersion().version();
	}
	
	public static String versionName() {
		return SharedConstants.getCurrentVersion().name();
	}
}
