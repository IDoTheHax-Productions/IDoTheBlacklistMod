package net.idothehax.idotheblacklist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.idothehax.idotheblacklist.common.BlacklistChecker;
import net.idothehax.idotheblacklist.common.Config;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class IDoTheBlacklist implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("blacklist");
    private static final Path CONFIG_PATH = Paths.get("config", "idotheblacklist.json");

    private final Config config = new Config(CONFIG_PATH, LOGGER);
    private final BlacklistChecker blacklistChecker;

    public IDoTheBlacklist() {
        String apiKey = config.loadApiKey();
        blacklistChecker = new BlacklistChecker(apiKey, LOGGER);
    }

    @Override
    public void onInitialize() {
        registerCommands();
        registerPlayerJoinEvent();
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    CommandManager.literal("setapikey")
                            .then(CommandManager.argument("key", StringArgumentType.string())
                                    .executes(context -> {
                                        ServerPlayerEntity player = context.getSource().getPlayer();
                                        if (player == null || !player.hasPermissionLevel(4)) {
                                            context.getSource().sendError(Text.literal("You must be an operator to use this command."));
                                            return 0;
                                        }
                                        String key = StringArgumentType.getString(context, "key");
                                        blacklistChecker.setApiKey(key);
                                        context.getSource().sendMessage(Text.literal("API key set successfully."));
                                        LOGGER.info("API key set by {}: {}", player.getName().getString(), key);
                                        config.saveApiKey(key);
                                        return 1;
                                    }))
                            .executes(context -> {
                                context.getSource().sendError(Text.literal("Please provide an API key: /setapikey <key>, you can get one in the ")
                                        .append(Text.literal("discord")
                                                .setStyle(Style.EMPTY
                                                        .withColor(TextColor.fromRgb(0x7289DA))
                                                        .withUnderline(true)
                                                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://discord.gg/aVYMFKRZGa"))
                                                )
                                        )
                                );
                                return 0;
                            })
            );
        });
    }

    private void registerPlayerJoinEvent() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            String playerUUID = player.getUuid().toString().replace("-", "");

            if (blacklistChecker.getApiKey() == null) {
                LOGGER.warn("API key not set. Use /setapikey <key> to configure it.");
                broadcastToOperators(server, "Blacklist check skipped for " + player.getName().getString() + ": API key not set.");
                return;
            }

            blacklistChecker.checkBlacklist(playerUUID).thenAcceptAsync(response -> {
                if (response == null) {
                    LOGGER.warn("No response from blacklist API for UUID {}. Allowing player.", playerUUID);
                    broadcastToOperators(server, "Blacklist check failed for " + player.getName().getString() + " due to network error.");
                    return;
                }

                if (response.getStatusCode() >= 500) {
                    LOGGER.error("API returned server error (Status {}) for UUID {}. Allowing player.", response.getStatusCode(), playerUUID);
                    broadcastToOperators(server, "Blacklist check failed for " + player.getName().getString() + " due to API error (Status " + response.getStatusCode() + ").");
                    return;
                }

                if (response.getStatusCode() == 401) {
                    LOGGER.error("API rejected request due to invalid or missing API key (Status 401) for UUID {}. Allowing player.", playerUUID);
                    broadcastToOperators(server, "Blacklist check failed for " + player.getName().getString() + " due to invalid API key.");
                    return;
                }

                if (response.getStatusCode() == 200) {
                    try {
                        JsonObject jsonResponse = JsonParser.parseString(response.getBody()).getAsJsonObject();
                        if (!jsonResponse.isJsonNull() && !jsonResponse.entrySet().isEmpty() && jsonResponse.has("reason")) {
                            String reason = jsonResponse.get("reason").getAsString();
                            String timestamp = jsonResponse.has("timestamp") ? jsonResponse.get("timestamp").getAsString() : "Time of ban unavailable";
                            server.execute(() -> handler.disconnect(Text.literal(
                                    "§l §bYou are blacklisted from this server.\n§r §cReason: " + reason + "\n§r §cTimestamp: " + timestamp
                            )));
                            broadcastToOperators(server, "Player " + player.getName().getString() + " was blacklisted. Reason: " + reason + " at " + timestamp);
                        } else {
                            LOGGER.info("Player {} (UUID: {}) is not blacklisted.", player.getName().getString(), playerUUID);
                        }
                    } catch (Exception e) {
                        LOGGER.error("Error parsing blacklist response for UUID {}: {}", playerUUID, response.getBody());
                        broadcastToOperators(server, "Blacklist check failed for " + player.getName().getString() + " due to invalid API response.");
                    }
                } else {
                    LOGGER.warn("Unexpected API response status {} for UUID {}. Allowing player.", response.getStatusCode(), playerUUID);
                }
            }, server);
        });
    }

    private void broadcastToOperators(MinecraftServer server, String message) {
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        for (ServerPlayerEntity player : players) {
            if (player.hasPermissionLevel(4)) {
                player.sendMessage(Text.literal(message));
            }
        }
    }
}