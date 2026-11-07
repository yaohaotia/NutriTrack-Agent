package com.myproject.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** LangChain4j owns the provider protocol; business budgets and safe transport stay explicit. */
@Component
public class AgentModelClient {
    public record Completion(AiMessage message, long promptTokens, long completionTokens) {}
    private final AgentConfig config;
    private final HttpClient http;

    public AgentModelClient(AgentConfig config) {
        this.config = config;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Completion complete(List<ChatMessage> messages, List<ToolSpecification> tools, long remainingMs) {
        if (remainingMs <= 0) throw new AgentException(504, "健康助手本轮处理超时，请缩小问题范围后重试");
        try {
            ChatModel model = model(Math.min(remainingMs, config.getRequestTimeoutSeconds() * 1000L));
            var response = model.chat(ChatRequest.builder().messages(List.copyOf(messages))
                    .toolSpecifications(tools).toolChoice(ToolChoice.AUTO).build());
            AiMessage message = response.aiMessage();
            if (message == null || (message.text() == null && !message.hasToolExecutionRequests())
                    || (message.text() != null && message.text().length() > 12000)) {
                throw new AgentException(502, "模型回复格式无效，请稍后重试");
            }
            TokenUsage usage = response.tokenUsage();
            return new Completion(message, usage == null ? 0 : nonNegative(usage.inputTokenCount()),
                    usage == null ? 0 : nonNegative(usage.outputTokenCount()));
        } catch (RuntimeException ex) {
            // The SDK can wrap transport errors; never expose its provider body or credentials.
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof AgentException agent) throw agent;
                if (cause instanceof dev.langchain4j.exception.TimeoutException) {
                    throw new AgentException(504, "模型请求超时，请稍后重试");
                }
            }
            throw new AgentException(502, "模型服务暂不可用，请检查配置或稍后重试");
        }
    }

    private ChatModel model(long timeoutMs) {
        // Reuse the JDK connection pool, but give each call its remaining time budget.
        return OpenAiChatModel.builder().baseUrl(config.getBaseUrl().replaceAll("/+$", ""))
                .apiKey(config.getApiKey()).modelName(config.getModel())
                .temperature(0.2).maxTokens(1000).maxRetries(0)
                .logRequests(false).logResponses(false).timeout(Duration.ofMillis(Math.max(1, timeoutMs)))
                .httpClientBuilder(BoundedModelHttpClient.builder(http))
                .customParameters(providerParameters()).build();
    }

    Map<String, Object> providerParameters() {
        // DeepSeek uses its non-thinking tool protocol; other providers get no extension.
        return "api.deepseek.com".equalsIgnoreCase(URI.create(config.getBaseUrl()).getHost())
                ? Map.of("thinking", Map.of("type", "disabled")) : Map.of();
    }

    private static long nonNegative(Integer value) { return value == null ? 0 : Math.max(0, value); }
}
