package org.worlddownloader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.rusherhack.client.api.events.world.EventChunk;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.setting.StringSetting;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Saves every chunk the server sends you into a singleplayer world in .minecraft/saves.
 */
public class DownloaderModule extends ToggleableModule {
	
	private final StringSetting name = new StringSetting("Name", "Save folder name. Empty = server address + timestamp", "");
	private final BooleanSetting blockEntities = new BooleanSetting("BlockEntities", "Save chests, signs, etc. (contents only as far as the server sent them)", true);
	private final NumberSetting<Integer> resaveRadius = new NumberSetting<>("ResaveRadius", "Chunk radius around you re-saved on stop / resave, to capture changes since load", 12, 0, 32).incremental(1);
	
	private WorldSaver saver;
	
	public DownloaderModule() {
		super("WorldDownloader", "Saves loaded chunks to a local world", ModuleCategory.WORLD);
		this.registerSettings(this.name, this.blockEntities, this.resaveRadius);
	}
	
	@Override
	public void onEnable() {
		final Minecraft mc = Minecraft.getInstance();
		String folder = this.name.getValue().trim();
		if(folder.isEmpty()) {
			final ServerData server = mc.getCurrentServer();
			final String host = server != null ? server.ip : "singleplayer";
			folder = host + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		}
		folder = folder.replaceAll("[^A-Za-z0-9._ -]", "_");
		
		final Path root = mc.gameDirectory.toPath().resolve("saves").resolve(folder);
		this.saver = new WorldSaver(root, folder, this.blockEntities.getValue());
		ChatUtils.print("[WDL] Saving to " + root);
		
		//capture what is already loaded
		this.resave();
	}
	
	@Override
	public void onDisable() {
		if(this.saver == null) {
			return;
		}
		this.resave();
		this.finish();
	}
	
	/** Re-serializes chunks around the player. Returns false when not in a world. */
	public boolean resave() {
		final Minecraft mc = Minecraft.getInstance();
		final ClientLevel level = mc.level;
		if(this.saver == null || level == null || mc.player == null) {
			return false;
		}
		final int r = this.resaveRadius.getValue();
		final int cx = Chunks.x(mc.player.chunkPosition()), cz = Chunks.z(mc.player.chunkPosition());
		for(int x = cx - r; x <= cx + r; x++) {
			for(int z = cz - r; z <= cz + r; z++) {
				final LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
				if(chunk != null) {
					this.saver.save(level, chunk);
				}
			}
		}
		return true;
	}
	
	public String status() {
		if(this.saver == null) {
			return "Not downloading";
		}
		return "Saved " + this.saver.getSavedCount() + " chunks (" + this.saver.getFailedCount() + " failed) to " + this.saver.getRoot();
	}
	
	private void finish() {
		final Minecraft mc = Minecraft.getInstance();
		final net.minecraft.core.BlockPos spawn = mc.player != null ? mc.player.blockPosition() : net.minecraft.core.BlockPos.ZERO;
		final String summary = this.status();
		this.saver.finish(spawn);
		this.saver = null;
		ChatUtils.print("[WDL] Done. " + summary);
	}
	
	@Subscribe
	private void onChunkLoad(EventChunk.Load event) {
		final ClientLevel level = Minecraft.getInstance().level;
		if(this.saver != null && level != null) {
			this.saver.save(level, event.getChunk());
		}
	}
	
	@Subscribe
	private void onChunkUnload(EventChunk.Unload event) {
		final ClientLevel level = Minecraft.getInstance().level;
		if(this.saver == null || level == null) {
			return;
		}
		//grab the latest state if the chunk is still reachable
		final LevelChunk chunk = level.getChunkSource().getChunkNow(Chunks.x(event.getChunkPos()), Chunks.z(event.getChunkPos()));
		if(chunk != null) {
			this.saver.save(level, chunk);
		}
	}
}
