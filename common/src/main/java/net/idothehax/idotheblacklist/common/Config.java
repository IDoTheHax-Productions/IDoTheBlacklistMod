package net.idothehax.idotheblacklist.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    private final Path configPath;
    private final Logger logger; // Uses org.slf4j.Logger

    public Config(Path configPath, Logger logger) {
        this.configPath = configPath;
        this.logger = logger;
    }

    public String loadApiKey() {
        try {
            if (Files.exists(configPath)) {
                String content = Files.readString(configPath);
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                if (json.has("apiKey")) {
                    return json.get("apiKey").getAsString();
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load API key from config", e);
        }
        return null;
    }

    public void saveApiKey(String apiKey) {
        JsonObject json = new JsonObject();
        json.addProperty("apiKey", apiKey);
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, new Gson().toJson(json));
            logger.info("API key saved to config");
        } catch (IOException e) {
            logger.error("Failed to save API key to config", e);
        }
    }
}