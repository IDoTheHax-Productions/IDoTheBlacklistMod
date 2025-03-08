package net.idothehax.idotheblacklist.paper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.idothehax.idotheblacklist.common.BlacklistChecker;
import net.idothehax.idotheblacklist.common.Config;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class IDoTheBlacklist extends JavaPlugin implements Listener {
    private static final Logger LOGGER = LoggerFactory.getLogger("blacklist");
    private static final Path CONFIG_PATH = Paths.get("plugins", "IDoTheBlacklist", "config.json");

    private Config config;
    private BlacklistChecker blacklistChecker;

    @Override
    public void onEnable() {
        // Initialize config and blacklist checker
        config = new Config(CONFIG_PATH, LOGGER);
        String apiKey = config.loadApiKey();
        blacklistChecker = new BlacklistChecker(apiKey, LOGGER);

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        LOGGER.info("IDoTheBlacklist plugin enabled.");
    }

    @Override
    public void onDisable() {
        LOGGER.info("IDoTheBlacklist plugin disabled.");
    }

    @EventHandler
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        String playerName = event.getName();
        String playerUUIDString = event.getUniqueId().toString().replace("-", "");

        if (blacklistChecker.getApiKey() == null) {
            LOGGER.warn("API key not set. Use /setapikey <key> to configure it.");
            broadcastToOperators("Blacklist check skipped for " + playerName + ": API key not set.");
            return;
        }

        CompletableFuture<BlacklistChecker.BlacklistResponse> future = blacklistChecker.checkBlacklist(playerUUIDString);
        try {
            // Block and wait for the response synchronously
            BlacklistChecker.BlacklistResponse response = future.get(); // This blocks the thread
            if (response == null) {
                LOGGER.warn("No response from blacklist API for UUID {}. Allowing player.", playerUUIDString);
                broadcastToOperators("Blacklist check failed for " + playerName + " due to network error.");
                return;
            }

            if (response.getStatusCode() >= 500) {
                LOGGER.error("API returned server error (Status {}) for UUID {}. Allowing player.", response.getStatusCode(), playerUUIDString);
                broadcastToOperators("Blacklist check failed for " + playerName + " due to API error (Status " + response.getStatusCode() + ").");
                return;
            }

            if (response.getStatusCode() == 401) {
                LOGGER.error("API rejected request due to invalid or missing API key (Status 401) for UUID {}. Allowing player.", playerUUIDString);
                broadcastToOperators("Blacklist check failed for " + playerName + " due to invalid API key.");
                return;
            }

            if (response.getStatusCode() == 200) {
                try {
                    JsonObject jsonResponse = JsonParser.parseString(response.getBody()).getAsJsonObject();
                    if (!jsonResponse.isJsonNull() && !jsonResponse.entrySet().isEmpty() && jsonResponse.has("reason")) {
                        String reason = jsonResponse.get("reason").getAsString();
                        String timestamp = jsonResponse.has("timestamp") ? jsonResponse.get("timestamp").getAsString() : "Time of ban unavailable";
                        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                                "§l §bYou are blacklisted from this server.\n§r §cReason: " + reason + "\n§r §cTimestamp: " + timestamp);
                        broadcastToOperators("Player " + playerName + " was blacklisted. Reason: " + reason + " at " + timestamp);
                    } else {
                        LOGGER.info("Player {} (UUID: {}) is not blacklisted.", playerName, playerUUIDString);
                    }
                } catch (Exception e) {
                    LOGGER.error("Error parsing blacklist response for UUID {}: {}", playerUUIDString, response.getBody(), e);
                    broadcastToOperators("Blacklist check failed for " + playerName + " due to invalid API response.");
                }
            } else {
                LOGGER.warn("Unexpected API response status {} for UUID {}. Allowing player.", response.getStatusCode(), playerUUIDString);
            }
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Error checking blacklist for UUID {}: {}", playerUUIDString, e.getMessage());
            broadcastToOperators("Blacklist check failed for " + playerName + " due to network error.");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("setapikey")) {
            if (!(sender instanceof Player player) || !player.hasPermission("idotheblacklist.setapikey")) {
                sender.sendMessage("§cYou must be an operator to use this command.");
                return true;
            }

            if (args.length == 0) {
                sender.sendMessage("§cPlease provide an API key: /setapikey <key>, you can get one in the discord: §9§uhttps://discord.gg/aVYMFKRZGa");
                return true;
            }

            String key = args[0];
            blacklistChecker.setApiKey(key);
            sender.sendMessage("§aAPI key set successfully.");
            LOGGER.info("API key set by {}: {}", player.getName(), key);
            config.saveApiKey(key);
            return true;
        }
        return false;
    }

    private void broadcastToOperators(String message) {
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.hasPermission("idotheblacklist.operator")) {
                player.sendMessage("§e" + message);
            }
        }
    }
}