package net.lelux.minetrix;

import io.kamax.matrix.client._MatrixClient;
import io.kamax.matrix.client._SyncData;
import io.kamax.matrix.client.regular.MatrixHttpClient;
import io.kamax.matrix.client.regular.SyncOptions;
import io.kamax.matrix.event._MatrixEvent;
import io.kamax.matrix.hs._MatrixRoom;
import io.kamax.matrix.json.event.MatrixJsonRoomMessageEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.stream.Collectors;

public class Main extends JavaPlugin implements Listener {

    private URL matrixServer;
    private String matrixAccessToken;
    private String matrixRoomId;

    private _MatrixClient client;
    private _MatrixRoom room;

    private Thread matrixThread;

    @Override
    public void onEnable() {
        loadConfig();
        connectToMatrix();
        getServer().getPluginManager().registerEvents(this, this);
        matrixThread.start();
    }

    @Override
    public void onDisable() {
        matrixThread.interrupt();
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent e) {
        room.sendFormattedText("<strong>" + e.getPlayer().getName() + "</strong>:<br>" + e.getMessage(),
                "**" + e.getPlayer().getName() + "**:\n" + e.getMessage());
    }

    private void loadConfig() {
        FileConfiguration c = getConfig();

        c.addDefault("matrix.server", "https://matrix.lelux.net");
        c.addDefault("matrix.access_token", "YOUR_ACCESS_TOKEN");
        c.addDefault("matrix.room_id", "!room:lelux.net");

        c.options().copyDefaults(true);

        saveConfig();
        reloadConfig();

        try {
            matrixServer = new URL(c.getString("matrix.server"));
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        matrixAccessToken = c.getString("matrix.access_token");
        matrixRoomId = c.getString("matrix.room_id");
    }

    private void connectToMatrix() {
        client = new MatrixHttpClient(matrixServer);

        client.setAccessToken(matrixAccessToken);

        room = client.getRoom(matrixRoomId);

        matrixThread = new Thread("Matrix Sync") {
            public void run() {
                String syncToken = null;
                while (!Thread.currentThread().isInterrupted()) {
                    _SyncData data = client.sync(SyncOptions.build().setSince(syncToken).get());

                    for (_SyncData.JoinedRoom jRoom : data.getRooms().getJoined()) {
                        if (jRoom.getId().equals(matrixRoomId)) {
                            for (_MatrixEvent event : jRoom.getTimeline().getEvents()) {
                                if ("m.room.message".contentEquals(event.getType())) {
                                    MatrixJsonRoomMessageEvent msg = new MatrixJsonRoomMessageEvent(event.getJson());

                                    if (!client.getUser().get().getId().equals(msg.getSender().getId())) {
                                        String sender = client.getUser(msg.getSender()).getName().get();
                                        String message = msg.getBody();

                                        if (message.startsWith("!")) {
                                            if (message.equalsIgnoreCase("!tab")) {
                                                room.sendText(getServer().getOnlinePlayers().stream()
                                                        .map(p -> p.getName())
                                                        .collect(Collectors.joining("\n")));
                                            } else {
                                                room.sendText("There is no such command");
                                            }
                                        } else {
                                            Bukkit.broadcastMessage("[" + sender + "] " + message);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    syncToken = data.nextBatchToken();
                }
            }
        };
    }
}
