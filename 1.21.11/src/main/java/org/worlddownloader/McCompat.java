package org.worlddownloader;

import net.minecraft.SharedConstants;

/** Version specific Minecraft accessors. Same API in every version module. */
public final class McCompat {
	private McCompat() {}
	
	public static int dataVersion() {
		return SharedConstants.getCurrentVersion().dataVersion().version();
	}
	
	public static String versionName() {
		return SharedConstants.getCurrentVersion().name();
	}
	
	/** 26.x moved dimensions to dimensions/minecraft/<dim>/ and world generation settings out of level.dat. */
	public static boolean modernSaveFormat() {
		return false;
	}
}
