package net.idothehax.idotheblacklist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class IDoTheBlacklist implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("blacklist");
    private static final String BLACKLIST_API_URL = "http://srv1.idothehax.com:5000/check_blacklist/";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static String API_KEY = null;
    private static final Path CONFIG_PATH = Paths.get("config", "idotheblacklist.json");

    @Override
    public void onInitialize() {
        loadApiKey();

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
                                        API_KEY = key;
                                        context.getSource().sendMessage(Text.literal("API key set successfully."));
                                        LOGGER.info("API key set by {}: {}", player.getName().getString(), key);
                                        saveApiKey(key);
                                        return 1;
                                    }))
                            .executes(context -> {
                                context.getSource().sendError(Text.literal("Please provide an API key: /setapikey <key>"));
                                return 0;
                            })
            );
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            String playerUUID = player.getUuid().toString().replace("-", "");

            if (API_KEY == null) {
                LOGGER.warn("API key not set. Use /setapikey <key> to configure it.");
                broadcastToOperators(server, "Blacklist check skipped for " + player.getName().getString() + ": API key not set.");
                return;
            }

            CompletableFuture.supplyAsync(() -> {
                try {
                    String apiUrl = BLACKLIST_API_URL + playerUUID;
                    LOGGER.info("Checking blacklist for URL: {}", apiUrl);

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(apiUrl))
                            .timeout(Duration.ofSeconds(5))
                            .header("Accept", "application/json")
                            .header("X-API-Key", API_KEY)
                            .GET()
                            .build();

                    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                    LOGGER.info("Blacklist API Response (Status {}): {}", response.statusCode(), response.body());
                    return new Response(response.statusCode(), response.body());
                } catch (Exception e) {
                    LOGGER.error("Error checking blacklist for UUID {}: {}", playerUUID, e.getMessage());
                    return null;
                }
            }).thenAcceptAsync(response -> {
                if (response == null) {
                    LOGGER.warn("No response from blacklist API for UUID {}. Allowing player.", playerUUID);
                    broadcastToOperators(server, "Blacklist check failed for " + player.getName().getString() + " due to network error.");
                    return;
                }

                if (response.statusCode >= 500) {
                    LOGGER.error("API returned server error (Status {}) for UUID {}. Allowing player.", response.statusCode, playerUUID);
                    broadcastToOperators(server, "Blacklist check failed for " + player.getName().getString() + " due to API error (Status " + response.statusCode + ").");
                    return;
                }

                if (response.statusCode == 401) {
                    LOGGER.error("API rejected request due to invalid or missing API key (Status 401) for UUID {}. Allowing player.", playerUUID);
                    broadcastToOperators(server, "Blacklist check failed for " + player.getName().getString() + " due to invalid API key.");
                    return;
                }

                if (response.statusCode == 200) {
                    try {
                        JsonObject jsonResponse = JsonParser.parseString(response.body).getAsJsonObject();
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
                        LOGGER.error("Error parsing blacklist response for UUID {}: {}", playerUUID, response.body, e);
                        broadcastToOperators(server, "Blacklist check failed for " + player.getName().getString() + " due to invalid API response.");
                    }
                } else {
                    LOGGER.warn("Unexpected API response status {} for UUID {}. Allowing player.", response.statusCode, playerUUID);
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

    private void loadApiKey() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                String jsonContent = Files.readString(CONFIG_PATH);
                JsonObject config = JsonParser.parseString(jsonContent).getAsJsonObject();
                if (config.has("api_key")) {
                    API_KEY = config.get("api_key").getAsString();
                    LOGGER.info("Loaded API key from config: {}", API_KEY);
                }
            } else {
                LOGGER.info("No config file found at {}. A new one will be created when the API key is set.", CONFIG_PATH);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load API key from config file: {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Error parsing config file: {}", e.getMessage());
        }
    }

    private void saveApiKey(String key) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject config = new JsonObject();
            config.addProperty("api_key", key);
            Files.writeString(CONFIG_PATH, config.toString());
            LOGGER.info("Saved API key to config file: {}", CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.error("Failed to save API key to config file: {}", e.getMessage());
        }
    }

    // Helper class to store response status and body
    private static class Response {
        final int statusCode;
        final String body;

        Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }
}