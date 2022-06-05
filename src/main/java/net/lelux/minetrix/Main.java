package net.lelux.minetrix;

import io.kamax.matrix.client.MatrixHttpRoom;
import io.kamax.matrix.client._MatrixClient;
import io.kamax.matrix.client._SyncData;
import io.kamax.matrix.client.regular.MatrixHttpClient;
import io.kamax.matrix.client.regular.SyncOptions;
import io.kamax.matrix.event._MatrixEvent;
import io.kamax.matrix.hs._MatrixRoom;
import io.kamax.matrix.json.event.MatrixJsonRoomMessageEvent;

import org.apache.commons.io.IOUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.reader.StreamReader;

import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

public class Main extends JavaPlugin implements Listener {

	URL matrixServer;
	String matrixAccessToken;
	String matrixRoomId;

	_MatrixClient client;
	_MatrixRoom room;

	Thread matrixThread;

	String serverStartFormat;
	String serverCloseFormat;
	String joinMsgFormat;
	String leaveMsgFormat;
	String deathMsgFormat;
	String chatMsgFormat;
	String advancementMsgFormat;

	String serverStartMsgType;
	String serverCloseMsgType;
	String joinMsgType;
	String leaveMsgType;
	String deathMsgType;
	String chatMsgType;
	String advancementMsgType;

	JsonObject locale;	

	@Override
	public void onEnable() {
		createConfig();
		loadConfig();
		loadLocale();
		connectToMatrix();
		getServer().getPluginManager().registerEvents(this, this);
		matrixThread.start();

		String html = serverStartFormat;
		sendMessage(room, serverStartMsgType, html, html);

			getCommand("reloadminetrix").setExecutor(new CommandExecutor() {
				@Override
				public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
					if(!sender.isOp()) return false;

					loadConfig();
					sender.sendMessage("Config reloaded");
					return true;
				}

			});
	}

	void loadLocale()
	{
		try
		{
			InputStream stream = getClass().getResourceAsStream("/en_us.json");
			String jstr = IOUtils.toString(stream, StandardCharsets.UTF_8);
			locale = (JsonObject)new JsonParser().parse(jstr);
		}
		catch(Exception e)
		{
			e.printStackTrace();
		}
	}

	String getTranslation(String key)
	{
		JsonElement o = locale.get(key);
		if(o == null) return null;

		return o.getAsString();
	}

	@Override
	public void onDisable() {
		String html = serverCloseFormat;
		sendMessage(room, serverCloseMsgType, html, html);
		matrixThread.interrupt();
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerChat(AsyncPlayerChatEvent e) {
		String html = chatMsgFormat
				.replace("%player%", e.getPlayer().getDisplayName())
				.replace("%message%", e.getMessage());

		sendMessage(room, chatMsgType, html, html);
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent e) {
		String html = joinMsgFormat
				.replace("%player%", e.getPlayer().getDisplayName());

		sendMessage(room, joinMsgType, html, html);
	}

	@EventHandler
	public void onPlayerLeave(PlayerQuitEvent e) {
		String html = leaveMsgFormat
				.replace("%player%", e.getPlayer().getDisplayName());

		sendMessage(room, leaveMsgType, html, html);
	}

	@EventHandler
	public void onPlayerDeath(PlayerDeathEvent e) {
		String html = deathMsgFormat
				.replace("%deathmsg%", e.getDeathMessage())
				.replace("%player%", e.getEntity().getDisplayName())
				.replace("%cause%", e.getEntity().getLastDamageCause().getEntity().getName());

		sendMessage(room, deathMsgType, html, html);
	}

	@EventHandler
	public void onAdvancement(PlayerAdvancementDoneEvent ev)
	{
		String advancementName = ev.getAdvancement().getKey().getKey();
		if(advancementName.startsWith("recipes")) return;

		advancementName = "advancements." + advancementName.replace("/", ".") + ".title";
		String translated = getTranslation(advancementName);
		if(translated != null)
			advancementName = translated;
	
		String html = advancementMsgFormat
			.replace("%player%", ev.getPlayer().getDisplayName())
			.replace("%advancement%", advancementName);

		sendMessage(room, advancementMsgType, html, html);
	}

	String removeColor(String raw) {
		return raw.replaceAll("§[0-fklmnor]", "");
	}

	void createConfig()
	{
		FileConfiguration c = getConfig();

		c.addDefault("matrix.server", "https://matrix.lelux.net");
		c.addDefault("matrix.access_token", "YOUR_ACCESS_TOKEN");
		c.addDefault("matrix.room_id", "!roomid:lelux.net");

		c.addDefault("format.serverStart", "Server started");
		c.addDefault("format.serverClose", "Server closed");
		c.addDefault("format.join", "%player% joined the game");
		c.addDefault("format.leave", "%player% left the game");
		c.addDefault("format.death", "%deathmsg%");
		c.addDefault("format.chat", "<%player%> %message%");
		c.addDefault("format.advancement", "%player% has made the advancement <font color=\"#44ff44\">[%advancement%]</font>");

		c.addDefault("msgtype.serverStart", "m.notice");
		c.addDefault("msgtype.serverClose", "m.notice");
		c.addDefault("msgtype.join", "m.notice");
		c.addDefault("msgtype.leave", "m.notice");
		c.addDefault("msgtype.death", "m.notice");
		c.addDefault("msgtype.chat", "m.text");
		c.addDefault("msgtype.advancement", "nic.custom.confetti");

		c.options().copyDefaults(true);

		saveConfig();
	}

	void loadConfig() {
		reloadConfig();
		FileConfiguration c = getConfig();

		try {
			matrixServer = new URL(c.getString("matrix.server"));
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		matrixAccessToken = c.getString("matrix.access_token");
		matrixRoomId = c.getString("matrix.room_id");

		serverStartFormat = c.getString("format.serverStart");
		serverCloseFormat = c.getString("format.serverClose");
		joinMsgFormat = c.getString("format.join");
		leaveMsgFormat = c.getString("format.leave");
		deathMsgFormat = c.getString("format.death");
		chatMsgFormat = c.getString("format.chat");
		advancementMsgFormat = c.getString("format.advancement");
		
		serverStartMsgType = c.getString("msgtype.serverStart");
		serverCloseMsgType = c.getString("msgtype.serverClose");
		joinMsgType = c.getString("msgtype.join");
		leaveMsgType = c.getString("msgtype.leave");
		deathMsgType = c.getString("msgtype.death");
		chatMsgType = c.getString("msgtype.chat");
		advancementMsgType = c.getString("msgtype.advancement");
	}

	void connectToMatrix() {
		client = new MatrixHttpClient(matrixServer);

		client.setAccessToken(matrixAccessToken);

		room = client.getRoom(matrixRoomId);

		matrixThread = new Thread("Matrix Sync") {
			public void run() {
				String syncToken = null;
				while (!Thread.currentThread().isInterrupted()) {
					_SyncData data = client.sync(SyncOptions.build().setSince(syncToken).get());

					for (_SyncData.JoinedRoom jRoom : data.getRooms().getJoined()) {
						if (!jRoom.getId().equals(matrixRoomId)) {
							continue;
						}

						for (_MatrixEvent event : jRoom.getTimeline().getEvents()) {
							if (!"m.room.message".contentEquals(event.getType())) {
								continue;
							}

							MatrixJsonRoomMessageEvent msg = new MatrixJsonRoomMessageEvent(event.getJson());
							if (client.getUser().get().getId().equals(msg.getSender().getId())) {
								continue;
							}

							onMessage(client.getUser(msg.getSender()).getName().get(), msg.getBody());
						}
					}

					syncToken = data.nextBatchToken();
				}
			}
		};
	}

	void onMessage(String sender, String msg) {
		if (msg.startsWith("!")) {
			if (msg.equalsIgnoreCase("!tab")) {
				room.sendText(getServer().getOnlinePlayers().stream()
						.map(p -> p.getName())
						.collect(Collectors.joining("\n")));
			}
		} else {
			Bukkit.broadcastMessage("[" + removeColor(sender) + "] " + removeColor(msg));
		}
	}

	String sendMessage(_MatrixRoom room, String msgtype, String html, String fallback) {
		JsonObject json = new JsonObject();
		json.addProperty("msgtype", msgtype);
		json.addProperty("formatted_body", html);
		json.addProperty("format", "org.matrix.custom.html");
		json.addProperty("body", fallback);

		return room.sendEvent("m.room.message", json);
	}
}
