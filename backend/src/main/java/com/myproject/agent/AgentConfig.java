package com.myproject.agent;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;

@Data
@Component
@ConfigurationProperties(prefix = "nutritrack.agent")
public class AgentConfig {
    @lombok.ToString.Exclude
    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-flash";
    private boolean demoEnabled = true;
    private int maxIterations = 4;
    private int maxToolCalls = 8;
    private int maxHistoryMessages = 12;
    private int sessionTtlMinutes = 60;
    private int maxSessions = 500;
    private int requestTimeoutSeconds = 20;
    private int totalTimeoutSeconds = 45;

    @PostConstruct
    void validate() {
        URI uri = URI.create(baseUrl);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !("https".equals(uri.getScheme())
                || ("http".equals(uri.getScheme()) && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())))) {
            throw new IllegalArgumentException("Agent base-url must use HTTPS, or HTTP on localhost for testing");
        }
        if (model == null || model.isBlank() || model.length() > 100 || apiKey == null) {
            throw new IllegalArgumentException("Invalid Agent model/key configuration");
        }
        requireRange(maxIterations, 1, 8, "max-iterations");
        requireRange(maxToolCalls, 1, 16, "max-tool-calls");
        requireRange(maxHistoryMessages, 2, 40, "max-history-messages");
        if (maxHistoryMessages % 2 != 0) throw new IllegalArgumentException("max-history-messages must be even");
        requireRange(sessionTtlMinutes, 1, 1440, "session-ttl-minutes");
        requireRange(maxSessions, 1, 5000, "max-sessions");
        requireRange(requestTimeoutSeconds, 1, 60, "request-timeout-seconds");
        requireRange(totalTimeoutSeconds, 1, 120, "total-timeout-seconds");
    }

    private static void requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException("Invalid Agent " + name);
    }

    public String mode() {
        return !apiKey.isBlank() ? "live" : demoEnabled ? "demo" : "unavailable";
    }
}
