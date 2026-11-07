package com.myproject.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Public execution summaries contain tool results, never hidden model reasoning. */
public final class AgentModels {
    private AgentModels() {}
    public record ChatRequest(String message, String sessionId) {}
    public record Message(String role, String content) {}
    public record Source(String id, String title, String url, String excerpt, String documentId, Integer page, String chunkId, Integer documentVersion) {
        public Source(String id, String title, String url, String excerpt) { this(id, title, url, excerpt, null, null, null, null); }
    }
    public record Step(String tool, JsonNode arguments, String status, long durationMs, String summary) {}
    public record Usage(int modelCalls, int toolCalls, long promptTokens, long completionTokens, long durationMs) {}
    public record ChatResponse(String sessionId, String answer, String mode, List<Step> steps,
                               List<Source> sources, Usage usage) {}
    public record SessionView(String sessionId, List<Message> messages) {}
    public record Status(String mode, String model, List<String> tools, Map<String, Object> limits) {}
}
