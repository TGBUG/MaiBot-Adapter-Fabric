package cn.tgbug;

import cn.tgbug.maibot_adapter.config.ModConfig;
import cn.tgbug.maibot_adapter.ws.MaiBotWebSocketServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MaiBotAdapter implements ModInitializer {
    public static final String MOD_ID = "maibot-adapter";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private MaiBotWebSocketServer wsServer;
    private ModConfig config;

    @Override
    public void onInitialize() {
        config = ModConfig.load();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            wsServer = new MaiBotWebSocketServer(config);
            wsServer.setMinecraftServer(server);
            try {
                wsServer.start();
                LOGGER.info("MaiBot-Adapter WebSocket server started on port {}", config.port);
                wsServer.broadcastServerStart();
            } catch (Exception e) {
                LOGGER.error("Failed to start MaiBot WebSocket server", e);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (wsServer != null) {
                wsServer.broadcastServerStop();
                try {
                    wsServer.stop(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
            if (wsServer != null) {
                wsServer.broadcastPlayerJoin(listener.player.getGameProfile().name());
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
            if (wsServer != null) {
                wsServer.broadcastPlayerLeave(listener.player.getGameProfile().name());
            }
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, boundChatType) -> {
            if (wsServer != null) {
                wsServer.broadcastChat(
                        sender.getGameProfile().name(),
                        message.decoratedContent().getString()
                );
            }
        });

        LOGGER.info("MaiBot-Adapter initialized successfully!");
    }

}
