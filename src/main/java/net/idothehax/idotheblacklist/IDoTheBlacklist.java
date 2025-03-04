package net.idothehax.idotheblacklist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IDoTheBlacklist implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("blacklist");
    private static final String BLACKLIST_API_URL = "http://127.0.0.1:5000/check_blacklist/";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public void onInitialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            String playerUUID = player.getUuid().toString().replace("-", "");

            CompletableFuture.supplyAsync(() -> {
                try {
                    String apiUrl = BLACKLIST_API_URL + playerUUID;
                    LOGGER.info("Checking blacklist for URL: {}", apiUrl);

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(apiUrl))
                            .timeout(Duration.ofSeconds(5))
                            .header("Accept", "application/json")
                            .GET()
                            .build();

                    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                    LOGGER.info("Blacklist API Response: {}", response.body());
                    return response.body();
                } catch (Exception e) {
                    LOGGER.error("Error checking blacklist", e);
                    return null;
                }
            }).thenAcceptAsync(responseBody -> {
                if (responseBody != null) {
                    try {
                        JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
                        if (!jsonResponse.isJsonNull() && !jsonResponse.entrySet().isEmpty()) {
                            String reason = jsonResponse.has("reason") ? jsonResponse.get("reason").getAsString() : "No reason provided.";
                            String timestamp = jsonResponse.has("timestamp") ? jsonResponse.get("timestamp").getAsString() : "Time of ban unavailable";
                            server.execute(() -> handler.disconnect(Text.literal("§l §bYou are blacklisted from this server.\n§r §cReason: " + reason + "\n§r §cTimestamp: " + timestamp)));

                            // Notify ops about the happening
                            broadcastToOperators(server, "Player " + player.getName().getString() + " was blacklisted. Reason: " + reason + " at " + timestamp);

                        }
                    } catch (Exception e) {
                        LOGGER.error("Error parsing blacklist response: {}", responseBody, e);
                    }
                }
            });
        });
    }

    private void broadcastToOperators(MinecraftServer server, String message) {
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        for (ServerPlayerEntity player : players) {
            if (player.hasPermissionLevel(4)) { // Permission level 4 corresponds to operators
                player.sendMessage(Text.literal(message));
            }
        }
    }
}
