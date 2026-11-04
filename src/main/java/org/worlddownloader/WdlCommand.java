package org.worlddownloader;

import org.rusherhack.client.api.feature.command.Command;
import org.rusherhack.core.command.annotations.CommandExecutor;

public class WdlCommand extends Command {
	
	private final DownloaderModule module;
	
	public WdlCommand(DownloaderModule module) {
		super("wdl", "Control the world downloader");
		this.module = module;
	}
	
	@CommandExecutor
	private String status() {
		return this.module.status();
	}
	
	@CommandExecutor(subCommand = "start")
	private String start() {
		this.module.setToggled(true);
		return "Started";
	}
	
	@CommandExecutor(subCommand = "stop")
	private String stop() {
		this.module.setToggled(false);
		return "Stopped";
	}
	
	@CommandExecutor(subCommand = "resave")
	private String resave() {
		return this.module.resave() ? "Re-saved nearby chunks" : "Not downloading or not in a world";
	}
}
