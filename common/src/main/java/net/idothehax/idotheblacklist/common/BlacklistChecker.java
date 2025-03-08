package net.idothehax.idotheblacklist.common;

import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class BlacklistChecker {
    private static final String BLACKLIST_API_URL = "http://srv1.idothehax.com:5000/check_blacklist/";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private String apiKey;
    private final Logger logger; // Uses org.slf4j.Logger

    public BlacklistChecker(String apiKey, Logger logger) {
        this.apiKey = apiKey;
        this.logger = logger;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    public CompletableFuture<BlacklistResponse> checkBlacklist(String playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiUrl = BLACKLIST_API_URL + playerUuid;
                logger.info("Checking blacklist for URL: {}", apiUrl);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .timeout(Duration.ofSeconds(5))
                        .header("Accept", "application/json")
                        .header("X-API-Key", apiKey)
                        .GET()
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                logger.info("Blacklist API Response (Status {}): {}", response.statusCode(), response.body());
                return new BlacklistResponse(response.statusCode(), response.body());
            } catch (Exception e) {
                logger.error("Error checking blacklist for UUID {}: {}", playerUuid, e.getMessage());
                return null;
            }
        });
    }

    public static class BlacklistResponse {
        private final int statusCode;
        private final String body;

        public BlacklistResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }
    }
}