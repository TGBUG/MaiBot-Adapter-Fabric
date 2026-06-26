package cn.tgbug.maibot_adapter.ws;

import cn.tgbug.maibot_adapter.config.ModConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MaiBotWebSocketServer extends WebSocketServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("maibot-adapter");

    private final ModConfig config;
    private MinecraftServer minecraftServer;
    private final Map<WebSocket, Boolean> authMap = new ConcurrentHashMap<>();

    public MaiBotWebSocketServer(ModConfig config) {
        super(new InetSocketAddress(config.port));
        this.config = config;
        setConnectionLostTimeout(config.heartbeatTimeout);
    }

    public void setMinecraftServer(MinecraftServer server) {
        this.minecraftServer = server;
    }

    // ── WebSocketServer overrides ────────────────────────────────────

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOGGER.info("MaiBot client connected: {}", conn.getRemoteSocketAddress());
        if (config.authToken.isEmpty()) {
            authMap.put(conn, true);
            conn.send("{\"type\":\"auth_ok\"}");
        } else {
            authMap.put(conn, false);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOGGER.info("MaiBot client disconnected: {} code={} reason={}", conn.getRemoteSocketAddress(), code, reason);
        authMap.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        JsonObject json;
        try {
            json = JsonParser.parseString(message).getAsJsonObject();
        } catch (Exception e) {
            sendError(conn, "Invalid JSON");
            return;
        }

        if (!json.has("type")) {
            sendError(conn, "Missing type field");
            return;
        }

        String type = json.get("type").getAsString();

        // Only auth message allowed before authentication
        Boolean authed = authMap.get(conn);
        if ((authed == null || !authed) && !"auth".equals(type)) {
            sendError(conn, "Not authenticated");
            return;
        }

        switch (type) {
            case "auth" -> handleAuth(conn, json);
            case "command" -> handleCommand(conn, json);
            case "chat" -> handleChatFromBot(conn, json);
            case "ping" -> handlePing(conn);
            default -> sendError(conn, "Unknown type: " + type);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOGGER.error("WebSocket error for {}", conn != null ? conn.getRemoteSocketAddress() : "unknown", ex);
    }

    @Override
    public void onStart() {
        LOGGER.info("MaiBot WebSocket server started on port {}", getPort());
    }

    // ── Incoming message handlers ────────────────────────────────────

    private void handleAuth(WebSocket conn, JsonObject json) {
        String token = json.has("token") ? json.get("token").getAsString() : "";
        if (config.authToken.equals(token)) {
            authMap.put(conn, true);
            conn.send("{\"type\":\"auth_ok\"}");
            LOGGER.info("MaiBot client authenticated: {}", conn.getRemoteSocketAddress());
        } else {
            conn.send("{\"type\":\"auth_fail\",\"reason\":\"令牌无效\"}");
            LOGGER.warn("MaiBot auth failed from: {}", conn.getRemoteSocketAddress());
            conn.close();
        }
    }

    private void handleCommand(WebSocket conn, JsonObject json) {
        String requestId = json.get("request_id").getAsString();
        String command = json.get("command").getAsString();
        if (!command.startsWith("/")) {
            command = "/" + command;
        }

        if (minecraftServer == null) {
            sendCommandResult(conn, requestId, false, "Server not started");
            return;
        }

        String finalCommand = command;
        minecraftServer.execute(() -> {
            try {
                minecraftServer.getCommands().performPrefixedCommand(
                        minecraftServer.createCommandSourceStack(), finalCommand);
                sendCommandResult(conn, requestId, true, "Executed: " + finalCommand);
            } catch (Exception e) {
                sendCommandResult(conn, requestId, false, "Command failed: " + e.getMessage());
            }
        });
    }

    private void handleChatFromBot(WebSocket conn, JsonObject json) {
        String playerName = json.get("player_name").getAsString();
        String message = json.get("message").getAsString();

        if (minecraftServer == null) return;

        minecraftServer.execute(() -> {
            Component text = Component.literal("<" + playerName + "> " + message);
            minecraftServer.getPlayerList().broadcastSystemMessage(text, false);
        });
        LOGGER.info("[MaiBot chat] <{}> {}", playerName, message);
    }

    private void handlePing(WebSocket conn) {
        conn.send("{\"type\":\"pong\"}");
    }

    // ── Outgoing message builders ────────────────────────────────────

    private void sendError(WebSocket conn, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "error");
        json.addProperty("message", message);
        conn.send(json.toString());
    }

    private void sendCommandResult(WebSocket conn, String requestId, boolean success, String output) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "command_result");
        json.addProperty("request_id", requestId);
        json.addProperty("success", success);
        json.addProperty("output", output);
        conn.send(json.toString());
    }

    // ── Broadcast methods ────────────────────────────────────────────

    private void broadcastToAll(String json) {
        for (var entry : authMap.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                entry.getKey().send(json);
            }
        }
    }

    public void broadcastChat(String playerName, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "chat");
        json.addProperty("player_name", playerName);
        json.addProperty("message", message);
        json.addProperty("timestamp", System.currentTimeMillis() / 1000);
        broadcastToAll(json.toString());
    }

    public void broadcastPlayerJoin(String playerName) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "player_join");
        json.addProperty("player_name", playerName);
        broadcastToAll(json.toString());
    }

    public void broadcastPlayerLeave(String playerName) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "player_leave");
        json.addProperty("player_name", playerName);
        broadcastToAll(json.toString());
    }

    public void broadcastServerStart() {
        broadcastToAll("{\"type\":\"server_start\"}");
    }

    public void broadcastServerStop() {
        broadcastToAll("{\"type\":\"server_stop\"}");
    }
}
