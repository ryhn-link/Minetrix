package net.lelux.minetrix;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

import org.apache.commons.io.IOUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.kamax.matrix.client._MatrixClient;
import io.kamax.matrix.client._SyncData;
import io.kamax.matrix.client.regular.MatrixHttpClient;
import io.kamax.matrix.client.regular.SyncOptions;
import io.kamax.matrix.event._MatrixEvent;
import io.kamax.matrix.hs._MatrixRoom;
import io.kamax.matrix.json.event.MatrixJsonRoomMessageEvent;
import me.dadus33.chatitem.ChatItem;
import me.dadus33.chatitem.chatmanager.ChatManager;
import me.dadus33.chatitem.chatmanager.v1.PacketEditingChatManager;
import me.dadus33.chatitem.chatmanager.v2.ChatListenerChatManager;
import me.dadus33.chatitem.utils.ItemUtils;
import me.dadus33.chatitem.utils.Storage;

public class Main extends JavaPlugin implements Listener {

	URL matrixServer;
	String matrixAccessToken;
	String matrixRoomId;

	_MatrixClient client;
	_MatrixRoom room;

	Thread matrixThread;

	String serverStartFallbackFormat;
	String serverCloseFallbackFormat;
	String joinMsgFallbackFormat;
	String leaveMsgFallbackFormat;
	String deathMsgFallbackFormat;
	String chatMsgFallbackFormat;
	String advancementMsgFallbackFormat;

	String serverStartHTMLFormat;
	String serverCloseHTMLFormat;
	String joinMsgHTMLFormat;
	String leaveMsgHTMLFormat;
	String deathMsgHTMLFormat;
	String chatMsgHTMLFormat;
	String advancementMsgHTMLFormat;

	String serverStartMsgType;
	String serverCloseMsgType;
	String joinMsgType;
	String leaveMsgType;
	String deathMsgType;
	String chatMsgType;
	String advancementMsgType;

	String matrixMsgFormat;

	JsonObject locale;	

	@Override
	public void onEnable() {
		createConfig();
		loadConfig();
		loadLocale();
		connectToMatrix();
		getServer().getPluginManager().registerEvents(this, this);
		matrixThread.start();

		sendMessage(room, serverStartMsgType, serverStartHTMLFormat, serverStartFallbackFormat);

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
		sendMessage(room, serverCloseMsgType, serverCloseHTMLFormat, serverStartFallbackFormat);
		matrixThread.interrupt();
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerChat(AsyncPlayerChatEvent e) {
		
		String msg = htmlEscape(e.getMessage());

		if(getServer().getPluginManager().getPlugin("ChatItem") != null &&
			ChatItem.getInstance() != null)
		{
			Storage c = ChatItem.getInstance().getStorage();
			ItemStack item = ChatManager.getUsableItem(e.getPlayer());
			String itemstr = ChatManager.styleItem(e.getPlayer(), item, c);

			if(ItemUtils.isEmpty(item)) itemstr = (c.HAND_DISABLED ? c.PLACEHOLDERS.get(0) : c.HAND_NAME);

			itemstr = itemstr.replace("{name}", e.getPlayer().getName()).replace("{display-name}", e.getPlayer().getDisplayName());

			msg = msg.replace("[i]" + ChatManager.SEPARATOR + e.getPlayer().getName() , itemstr);
			msg = msg.replace("[i]", itemstr);
		}

		String html = chatMsgHTMLFormat
				.replace("%player%", toHtml(e.getPlayer().getDisplayName()))
				.replace("%message%", toHtml(msg));

		String fallback = chatMsgFallbackFormat
				.replace("%player%", removeColor(e.getPlayer().getDisplayName()))
				.replace("%message%", removeColor(msg));


		sendMessage(room, chatMsgType, html, fallback);
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent e) {
		String html = joinMsgHTMLFormat
				.replace("%player%", e.getPlayer().getDisplayName());

		String fallback = joinMsgFallbackFormat
				.replace("%player%", e.getPlayer().getDisplayName());

		sendMessage(room, joinMsgType, html, fallback);
	}

	@EventHandler
	public void onPlayerLeave(PlayerQuitEvent e) {
		String html = leaveMsgHTMLFormat
				.replace("%player%", e.getPlayer().getDisplayName());

		String fallback = leaveMsgFallbackFormat
				.replace("%player%", e.getPlayer().getDisplayName());

		sendMessage(room, leaveMsgType, html, fallback);
	}

	@EventHandler
	public void onPlayerDeath(PlayerDeathEvent e) {
		String html = deathMsgHTMLFormat
				.replace("%deathmsg%", e.getDeathMessage())
				.replace("%player%", e.getEntity().getDisplayName())
				.replace("%cause%", e.getEntity().getLastDamageCause().getEntity().getName());

		String fallback = deathMsgFallbackFormat
				.replace("%deathmsg%", e.getDeathMessage())
				.replace("%player%", e.getEntity().getDisplayName())
				.replace("%cause%", e.getEntity().getLastDamageCause().getEntity().getName());

		sendMessage(room, deathMsgType, html, fallback);
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
	
		String html = advancementMsgHTMLFormat
			.replace("%player%", ev.getPlayer().getDisplayName())
			.replace("%advancement%", advancementName);
		
		String fallback = advancementMsgFallbackFormat
			.replace("%player%", ev.getPlayer().getDisplayName())
			.replace("%advancement%", advancementName);

		sendMessage(room, advancementMsgType, html, fallback);
	}

	String removeColor(String raw) {
		return ChatColor.stripColor(raw);
	}

	String mcEscape(String str)
	{
		// Zero-width character
		Character zwnj = '‌';
		return str.replace("§", "§" + zwnj);
	}

	String htmlEscape(String str)
	{
		return str.replace("<", "&lt;").replace(">", "&gt;");
	}

	Color[] colors = new Color[]
	{
		Color.fromRGB(000),
		Color.fromRGB(000,000,170),
		Color.fromRGB(000,170,000),
		Color.fromRGB(000,170,170),
		Color.fromRGB(170,000,000),
		Color.fromRGB(170,000,170),
		Color.fromRGB(255, 170, 0),
		Color.fromRGB(170, 170, 170),
		Color.fromRGB(85,85,85),
		Color.fromRGB(85,85,255),
		Color.fromRGB(85,255,85),
		Color.fromRGB(85,255,255),
		Color.fromRGB(255,85,85),
		Color.fromRGB(255,85,255),
		Color.fromRGB(255,255,85),
		Color.fromRGB(255,255,255)
	};

	String toHtml(String str)
	{
		StringBuilder b = new StringBuilder();
		ArrayList<String> tags = new ArrayList<String>();
		int len = str.length();

		for(int i=0; i<len; i++)
		{
			Character ch = str.charAt(i);
			if(ch == '§' && i + 2 <= len)
			{
				Character code = str.charAt(i + 1);
				if("0123456789".indexOf(code) > -1)
				{
					int coli = -('0' - code);
					Color c = colors[coli];
					b.append("<font color=\"#" + String.format("%06X", c.asRGB()) + "\">");
					tags.add("font");
				}
				else if("abcdefg".indexOf(Character.toLowerCase(code)) > -1)
				{
					int coli = -('a' - Character.toLowerCase(code)) + 10;
					Color c = colors[coli];
					b.append("<font color=\"#" + String.format("%06X", c.asRGB()) + "\">");
					tags.add("font");
				}
				else if("klmnor".indexOf(Character.toLowerCase(code)) > -1)
				{
					code = Character.toLowerCase(code);
					switch(code)
					{
						case 'r':
							Collections.reverse(tags);
							for(String tag : tags)
							{
								b.append("</" + tag + ">");
							}
							tags.clear();
						break;
						case 'k':
							b.append("<span data-mx-spoiler>");
							tags.add("span");
						break;
						case 'l':
							b.append("<b>");
							tags.add("b");
						break;
						case 'm':
							b.append("<strike>");
							tags.add("strike");
						break;
						case 'n':
							b.append("<u>");
							tags.add("u");
						break;
						case 'o':
							b.append("<i>");
							tags.add("i");
						break;
					}
				}
				else
				{
					b.append(ch);
					b.append(code);
				}
				
				i++;
				continue;
			}

			b.append(ch);
		}
		
		return new String(b);
	}

	void createConfig()
	{
		FileConfiguration c = getConfig();

		c.addDefault("matrix.server", "https://matrix.lelux.net");
		c.addDefault("matrix.access_token", "YOUR_ACCESS_TOKEN");
		c.addDefault("matrix.room_id", "!roomid:lelux.net");

		c.addDefault("format.html.serverStart", "Server started");
		c.addDefault("format.html.serverClose", "Server closed");
		c.addDefault("format.html.join", "%player% joined the game");
		c.addDefault("format.html.leave", "%player% left the game");
		c.addDefault("format.html.death", "%deathmsg%");
		c.addDefault("format.html.chat", "&lt;%player%&gt; %message%");
		c.addDefault("format.html.advancement", "%player% has made the advancement <font color=\"#44ff44\">[%advancement%]</font>");

		c.addDefault("format.fallback.serverStart", "Server started");
		c.addDefault("format.fallback.serverClose", "Server closed");
		c.addDefault("format.fallback.join", "%player% joined the game");
		c.addDefault("format.fallback.leave", "%player% left the game");
		c.addDefault("format.fallback.death", "%deathmsg%");
		c.addDefault("format.fallback.chat", "<%player%> %message%");
		c.addDefault("format.fallback.advancement", "%player% has made the advancement [%advancement%]");

		c.addDefault("msgtype.serverStart", "m.notice");
		c.addDefault("msgtype.serverClose", "m.notice");
		c.addDefault("msgtype.join", "m.notice");
		c.addDefault("msgtype.leave", "m.notice");
		c.addDefault("msgtype.death", "m.notice");
		c.addDefault("msgtype.chat", "m.text");
		c.addDefault("msgtype.advancement", "m.notice"); // nic.custom.confetti disables HTML rendering

		c.addDefault("format.matrix", "[%displayname%] %body%");

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

		serverStartHTMLFormat = c.getString("format.html.serverStart");
		serverCloseHTMLFormat = c.getString("format.html.serverClose");
		joinMsgHTMLFormat = c.getString("format.html.join");
		leaveMsgHTMLFormat = c.getString("format.html.leave");
		deathMsgHTMLFormat = c.getString("format.html.death");
		chatMsgHTMLFormat = c.getString("format.html.chat");
		advancementMsgHTMLFormat = c.getString("format.html.advancement");

		serverStartFallbackFormat = c.getString("format.fallback.serverStart");
		serverCloseFallbackFormat = c.getString("format.fallback.serverClose");
		joinMsgFallbackFormat = c.getString("format.fallback.join");
		leaveMsgFallbackFormat = c.getString("format.fallback.leave");
		deathMsgFallbackFormat = c.getString("format.fallback.death");
		chatMsgFallbackFormat = c.getString("format.fallback.chat");
		advancementMsgFallbackFormat = c.getString("format.fallback.advancement");
		
		serverStartMsgType = c.getString("msgtype.serverStart");
		serverCloseMsgType = c.getString("msgtype.serverClose");
		joinMsgType = c.getString("msgtype.join");
		leaveMsgType = c.getString("msgtype.leave");
		deathMsgType = c.getString("msgtype.death");
		chatMsgType = c.getString("msgtype.chat");
		advancementMsgType = c.getString("msgtype.advancement");

		matrixMsgFormat = c.getString("format.matrix");
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

							onMessage(msg);
						}
					}

					syncToken = data.nextBatchToken();
				}
			}
		};
	}

	void onMessage(MatrixJsonRoomMessageEvent msg) {
		if(msg.getBodyType() == "m.notice") return;

		// Converting from HTML to MC formatting codes would be nice
		// if(msg.getFormat() == "org.matrix.custom.html")
		// 		msg.getFormattedBody()
		String body = mcEscape(msg.getBody());
		String displayname = client.getUser(msg.getSender()).getName().get();
		String username = msg.getSender().toString();

		String mcmsg = ChatColor.translateAlternateColorCodes('&', matrixMsgFormat)
			.replace("%displayname%", displayname)
			.replace("%username%", username)
			.replace("%body%", body);

		Bukkit.broadcastMessage(mcmsg);
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
