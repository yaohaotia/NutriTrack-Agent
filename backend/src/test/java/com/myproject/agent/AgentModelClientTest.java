package com.myproject.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AgentModelClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;

    @AfterEach void cleanup() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test void sendsStructuredProtocolAndParsesUsage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>(), auth = new AtomicReference<>();
        start(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"您好\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        AgentModelClient client = client();
        var completion = client.complete(messages(), toolSpecifications(), 5000);
        assertEquals("您好", completion.message().text()); assertEquals(10, completion.promptTokens());
        assertEquals(5, completion.completionTokens()); assertEquals("Bearer test-key", auth.get());
        assertEquals("auto", json.readTree(requestBody.get()).path("tool_choice").asText());
        assertEquals(1000, json.readTree(requestBody.get()).path("max_tokens").asInt());
        assertEquals("user", json.readTree(requestBody.get()).path("messages").get(0).path("role").asText());
        assertEquals("search_knowledge", json.readTree(requestBody.get()).path("tools").get(0)
                .path("function").path("name").asText());
    }

    @Test void deepSeekUsesNonThinkingToolProtocolOnlyForOfficialHost() {
        AgentConfig config = new AgentConfig();
        assertEquals(Map.of("thinking", Map.of("type", "disabled")), new AgentModelClient(config).providerParameters());
        config.setBaseUrl("http://localhost:8000/v1");
        assertTrue(new AgentModelClient(config).providerParameters().isEmpty());
        config.setBaseUrl("https://api.deepseek.com.example.org/v1");
        assertTrue(new AgentModelClient(config).providerParameters().isEmpty());
    }

    @Test void hidesProviderErrors() throws Exception {
        start(exchange -> {
            exchange.getRequestBody().readAllBytes(); byte[] response = "credential-secret-debug".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        var exception = assertThrows(AgentException.class, () -> client().complete(messages(), List.of(), 5000));
        assertEquals(502, exception.status()); assertFalse(exception.getMessage().contains("secret"));
    }

    @Test void rejectsMalformedSuccessfulResponse() throws Exception {
        start(exchange -> {
            exchange.getRequestBody().readAllBytes(); byte[] response = "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        assertEquals(502, assertThrows(AgentException.class, () -> client().complete(messages(), List.of(), 5000)).status());
    }

    @Test void timesOutBodyThatStallsAfterResponseHeaders() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        start(exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        try {
            var exception = assertThrows(AgentException.class, () -> client().complete(messages(), List.of(), 250));
            assertEquals(504, exception.status());
        } finally { release.countDown(); }
    }

    @Test void boundsResponseBodySize() throws Exception {
        start(exchange -> {
            exchange.getRequestBody().readAllBytes(); byte[] response = new byte[1_048_577];
            exchange.sendResponseHeaders(200, response.length);
            try { exchange.getResponseBody().write(response); } catch (java.io.IOException ignored) { /* Client cancels oversized body. */ }
            exchange.close();
        });
        assertEquals(502, assertThrows(AgentException.class, () -> client().complete(messages(), List.of(), 5000)).status());
    }

    @Test void providerFailureDoesNotTriggerHiddenSdkRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 500, "provider-secret-debug");
        });
        var exception = assertThrows(AgentException.class,
                () -> client().complete(messages(), toolSpecifications(), 5000));
        assertEquals(502, exception.status());
        assertEquals(1, calls.get(), "SDK retries must not exceed the explicit model-call budget");
        assertFalse(exception.getMessage().contains("secret"));
    }

    @Test void frameworkSerializesToolCallAndMatchingResultAcrossModelRequests() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JsonNode> firstBody = new AtomicReference<>(), secondBody = new AtomicReference<>();
        start(exchange -> {
            JsonNode body = json.readTree(exchange.getRequestBody().readAllBytes());
            if (calls.incrementAndGet() == 1) {
                firstBody.set(body);
                respond(exchange, 200, """
                        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                          {"id":"call1","type":"function","function":{"name":"search_knowledge",
                           "arguments":"{\\"query\\":\\"控盐\\"}"}}
                        ]},"finish_reason":"tool_calls"}]}
                        """);
            } else {
                secondBody.set(body);
                respond(exchange, 200, """
                        {"choices":[{"message":{"role":"assistant","content":"留意调味品中的钠 [WHO-SODIUM]"},
                         "finish_reason":"stop"}]}
                        """);
            }
        });

        AgentModelClient client = client();
        List<ChatMessage> messages = new ArrayList<>(messages());
        var first = client.complete(messages, toolSpecifications(), 5000);
        assertEquals(1, first.message().toolExecutionRequests().size());
        var toolCall = first.message().toolExecutionRequests().get(0);
        assertEquals("call1", toolCall.id());
        assertEquals("search_knowledge", toolCall.name());
        assertEquals("控盐", json.readTree(toolCall.arguments()).path("query").asText());
        messages.add(first.message());
        messages.add(ToolExecutionResultMessage.from(toolCall, "{\"sources\":[{\"id\":\"WHO-SODIUM\"}]}"));
        var second = client.complete(messages, toolSpecifications(), 5000);

        assertEquals(2, calls.get());
        assertTrue(second.message().text().contains("WHO-SODIUM"));
        assertEquals("object", firstBody.get().path("tools").get(0)
                .path("function").path("parameters").path("type").asText());
        var assistant = secondBody.get().path("messages").get(1);
        assertEquals("assistant", assistant.path("role").asText());
        assertEquals("function", assistant.path("tool_calls").get(0).path("type").asText());
        assertEquals("call1", assistant.path("tool_calls").get(0).path("id").asText());
        var result = secondBody.get().path("messages").get(2);
        assertEquals("tool", result.path("role").asText());
        assertEquals("call1", result.path("tool_call_id").asText());
        assertEquals("WHO-SODIUM", json.readTree(result.path("content").asText())
                .path("sources").get(0).path("id").asText());
    }

    @Test void rejectsMalformedToolCallsBeforeSdkCanDiscardProtocolFields() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        start(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, body.get());
        });
        var malformed = List.of(
                "{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"arguments\":\"{}\"}}",
                "{\"type\":\"function\",\"function\":{\"name\":\"search_knowledge\",\"arguments\":\"{}\"}}",
                "{\"id\":\"call1\",\"function\":{\"name\":\"search_knowledge\",\"arguments\":\"{}\"}}",
                "{\"id\":\"call1\",\"type\":\"other\",\"function\":{\"name\":\"search_knowledge\",\"arguments\":\"{}\"}}"
        );
        for (String call : malformed) {
            body.set("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" + call + "]}}]}");
            var exception = assertThrows(AgentException.class,
                    () -> client().complete(messages(), toolSpecifications(), 5000), call);
            assertEquals(502, exception.status());
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static List<ChatMessage> messages() { return List.of(UserMessage.from("怎么控盐")); }

    private static List<ToolSpecification> toolSpecifications() {
        return List.of(ToolSpecification.builder().name("search_knowledge").description("搜索营养资料")
                .parameters(JsonObjectSchema.builder().addStringProperty("query", "营养关键词")
                        .required("query").additionalProperties(false).build()).build());
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", handler); executor = Executors.newCachedThreadPool(); server.setExecutor(executor); server.start();
    }
    private AgentModelClient client() {
        AgentConfig config = new AgentConfig(); config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setApiKey("test-key"); return new AgentModelClient(config);
    }
}
