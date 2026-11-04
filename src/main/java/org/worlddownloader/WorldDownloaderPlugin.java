package org.worlddownloader;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.plugin.Plugin;

public class WorldDownloaderPlugin extends Plugin {
	
	private DownloaderModule module;
	
	@Override
	public void onLoad() {
		this.module = new DownloaderModule();
		RusherHackAPI.getModuleManager().registerFeature(this.module);
		RusherHackAPI.getCommandManager().registerFeature(new WdlCommand(this.module));
	}
	
	@Override
	public void onUnload() {
		if(this.module != null && this.module.isToggled()) {
			this.module.setToggled(false);
		}
	}
}
