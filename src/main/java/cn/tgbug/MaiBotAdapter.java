package cn.tgbug;

import cn.tgbug.maibot_adapter_fabric.config.ModConfig;
import cn.tgbug.maibot_adapter_fabric.ws.MaiBotWebSocketServer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

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

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> registerCommands(dispatcher));

        LOGGER.info("MaiBot-Adapter initialized successfully!");
    }

    // ── /maia command ────────────────────────────────────────────────

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                literal("maia")
                        .then(literal("status").executes(this::executeStatus))
                        .then(literal("config")
                                .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                                .then(literal("port")
                                        .executes(ctx -> executeConfigGet(ctx, "port"))
                                        .then(argument("value", IntegerArgumentType.integer(1, 65535))
                                                .executes(ctx -> executeConfigSet(ctx, "port"))))
                                .then(literal("authToken")
                                        .executes(ctx -> executeConfigGet(ctx, "authToken"))
                                        .then(argument("value", StringArgumentType.string())
                                                .executes(ctx -> executeConfigSet(ctx, "authToken"))))
                                .then(literal("heartbeatTimeout")
                                        .executes(ctx -> executeConfigGet(ctx, "heartbeatTimeout"))
                                        .then(argument("value", IntegerArgumentType.integer(1))
                                                .executes(ctx -> executeConfigSet(ctx, "heartbeatTimeout")))))
                        .then(literal("reload")
                                .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                                .executes(this::executeReload)));
    }

    private int executeStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (wsServer == null) {
            source.sendFailure(Component.literal("WebSocket server is not running"));
            return 0;
        }
        int port = config.port;
        int clients = wsServer.getAuthenticatedClientCount();
        source.sendSuccess(
                () -> Component.literal("WebSocket server running on port " + port
                        + ", connected clients: " + clients),
                false);
        return 1;
    }

    private int executeConfigGet(CommandContext<CommandSourceStack> ctx, String key) {
        CommandSourceStack source = ctx.getSource();
        String value = switch (key) {
            case "port" -> String.valueOf(config.port);
            case "authToken" -> config.authToken.isEmpty() ? "(empty)" : config.authToken;
            case "heartbeatTimeout" -> String.valueOf(config.heartbeatTimeout);
            default -> "unknown";
        };
        source.sendSuccess(
                () -> Component.literal("Config " + key + " = " + value),
                false);
        return 1;
    }

    private int executeConfigSet(CommandContext<CommandSourceStack> ctx, String key) {
        CommandSourceStack source = ctx.getSource();
        switch (key) {
            case "port" -> {
                int value = IntegerArgumentType.getInteger(ctx, "value");
                config.port = value;
                config.save();
                source.sendSuccess(
                        () -> Component.literal("Config port set to " + value
                                + " (use /maia reload to apply)"),
                        false);
            }
            case "authToken" -> {
                String value = StringArgumentType.getString(ctx, "value");
                if (value.equals("clear") || value.equals("none") || value.equals("\"\"")) {
                    config.authToken = "";
                } else {
                    config.authToken = value;
                }
                config.save();
                String display = config.authToken.isEmpty() ? "(empty)" : config.authToken;
                source.sendSuccess(
                        () -> Component.literal("Config authToken set to " + display),
                        false);
            }
            case "heartbeatTimeout" -> {
                int value = IntegerArgumentType.getInteger(ctx, "value");
                config.heartbeatTimeout = value;
                config.save();
                source.sendSuccess(
                        () -> Component.literal("Config heartbeatTimeout set to " + value
                                + " (use /maia reload to apply)"),
                        false);
            }
        }
        return 1;
    }

    private int executeReload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        config = ModConfig.reload();

        if (wsServer != null) {
            wsServer.broadcastServerStop();
            try {
                wsServer.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        wsServer = new MaiBotWebSocketServer(config);
        wsServer.setMinecraftServer(source.getServer());
        try {
            wsServer.start();
            wsServer.broadcastServerStart();
            source.sendSuccess(
                    () -> Component.literal(
                            "Config reloaded and WebSocket server restarted on port " + config.port),
                    false);
        } catch (Exception e) {
            LOGGER.error("Failed to restart WebSocket server", e);
            source.sendFailure(
                    Component.literal("Failed to restart WebSocket server: " + e.getMessage()));
            return 0;
        }

        return 1;
    }
}
