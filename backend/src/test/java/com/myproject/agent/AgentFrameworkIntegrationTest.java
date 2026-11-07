package com.myproject.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myproject.entity.MealRecord;
import com.myproject.entity.User;
import com.myproject.mapper.MealRecordMapper;
import com.myproject.mapper.UserMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static com.myproject.agent.AgentModels.ChatRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises the real LangChain4j protocol and business loop against a local provider stub. */
class AgentFrameworkIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void cleanup() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void frameworkRunsOwnedMealAndKnowledgeToolsAndSendsResultsAndHistoryBack() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        String firstAnswer = "已记录热量合计350 kcal，另有1条热量缺失。控盐时留意酱料中的钠 [WHO-SODIUM]";
        String followUpAnswer = "这2条记录中1条热量缺失；控盐建议仍需结合实际记录 [WHO-SODIUM]";
        start(exchange -> {
            requests.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            int index = calls.getAndIncrement();
            byte[] body = switch (index) {
                case 0, 2 -> toolResponse();
                case 1 -> finalResponse(firstAnswer);
                case 3 -> finalResponse(followUpAnswer);
                default -> "unexpected extra model request".getBytes(StandardCharsets.UTF_8);
            };
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(index < 4 ? 200 : 500, body.length);
            try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
        });

        AgentConfig config = config();
        AgentMemory memory = new AgentMemory(config);
        UserMapper users = mock(UserMapper.class);
        User alice = new User(); alice.setId(7L); alice.setUsername("alice");
        when(users.selectByUsername("alice")).thenReturn(alice);
        MealRecordMapper meals = mock(MealRecordMapper.class);
        when(meals.selectList(any())).thenReturn(List.of(meal(1L, 350), meal(2L, null)));
        AgentTools tools = new AgentTools(meals, new KnowledgeSearch(json), json,
                Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC));
        AgentService service = new AgentService(config, memory, tools, new AgentModelClient(config), users, json);

        String question = "汇总我2026-10-24至2026-10-30的饮食记录，并给出控盐参考";
        var response = service.chat("alice", new ChatRequest(question, null));

        assertEquals("live", response.mode());
        assertEquals(firstAnswer, response.answer());
        assertEquals(2, response.usage().modelCalls());
        assertEquals(2, response.usage().toolCalls());
        assertEquals(250, response.usage().promptTokens());
        assertEquals(50, response.usage().completionTokens());
        assertEquals(List.of("summarize_meals", "search_knowledge"),
                response.steps().stream().map(AgentModels.Step::tool).toList());
        assertTrue(response.steps().stream().allMatch(step -> "success".equals(step.status())));
        assertEquals(1, response.sources().size());
        assertEquals("WHO-SODIUM", response.sources().get(0).id());
        assertTrue(response.sources().get(0).url().startsWith("https://www.who.int/"));
        assertEquals(2, calls.get(), "Only the requested tool round and answer round may reach the provider");

        ArgumentCaptor<QueryWrapper> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(meals).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("user_id"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(7L));

        JsonNode initialRequest = requests.get(0);
        assertEquals("test-integration-model", initialRequest.path("model").asText());
        assertEquals(3, initialRequest.path("tools").size());
        assertEquals("auto", initialRequest.path("tool_choice").asText());
        JsonNode secondMessages = requests.get(1).path("messages");
        assertEquals(5, secondMessages.size());
        assertEquals("assistant", secondMessages.get(2).path("role").asText());
        assertEquals(2, secondMessages.get(2).path("tool_calls").size());
        assertEquals("tool", secondMessages.get(3).path("role").asText());
        assertEquals("meal_call", secondMessages.get(3).path("tool_call_id").asText());
        JsonNode summary = json.readTree(secondMessages.get(3).path("content").asText());
        assertEquals(2, summary.path("recordCount").asInt());
        assertEquals(350, summary.path("recordedCalories").asInt());
        assertEquals(1, summary.path("missingCaloriesCount").asInt());
        assertFalse(summary.path("truncated").asBoolean());
        assertEquals("tool", secondMessages.get(4).path("role").asText());
        assertEquals("knowledge_call", secondMessages.get(4).path("tool_call_id").asText());
        JsonNode sources = json.readTree(secondMessages.get(4).path("content").asText()).path("sources");
        assertEquals("WHO-SODIUM", sources.get(0).path("id").asText());
        var history = memory.view("alice", response.sessionId()).messages();
        assertEquals(2, history.size());
        assertEquals(question, history.get(0).content());
        assertEquals(firstAnswer, history.get(1).content());

        String followUpQuestion = "这些记录里有几条没有填写热量，再给我控盐参考";
        var followUp = service.chat("alice", new ChatRequest(followUpQuestion, response.sessionId()));
        assertEquals(response.sessionId(), followUp.sessionId());
        assertEquals(followUpAnswer, followUp.answer());
        assertEquals(2, followUp.usage().modelCalls());
        assertEquals(4, calls.get(), "Follow-up also needs exactly two provider requests without hidden retries");
        JsonNode followUpMessages = requests.get(2).path("messages");
        assertEquals(4, followUpMessages.size());
        assertEquals("user", followUpMessages.get(1).path("role").asText());
        assertEquals(question, followUpMessages.get(1).path("content").asText());
        assertEquals("assistant", followUpMessages.get(2).path("role").asText());
        assertEquals(firstAnswer, followUpMessages.get(2).path("content").asText());
        assertEquals(followUpQuestion, followUpMessages.get(3).path("content").asText());
        assertEquals(4, memory.view("alice", response.sessionId()).messages().size());
    }

    @Test
    void frameworkDoesNotRetryProviderFailureOrPersistAnIncompleteTurn() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            byte[] body = "private-provider-debug".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
        });
        AgentConfig config = config();
        AgentMemory memory = new AgentMemory(config);
        UserMapper users = mock(UserMapper.class);
        User alice = new User(); alice.setId(7L); alice.setUsername("alice");
        when(users.selectByUsername("alice")).thenReturn(alice);
        MealRecordMapper meals = mock(MealRecordMapper.class);
        AgentTools tools = new AgentTools(meals, new KnowledgeSearch(json), json);
        AgentService service = new AgentService(config, memory, tools, new AgentModelClient(config), users, json);

        var exception = assertThrows(AgentException.class,
                () -> service.chat("alice", new ChatRequest("汇总我的饮食记录", null)));
        assertEquals(502, exception.status());
        assertFalse(exception.getMessage().contains("private-provider-debug"));
        assertEquals(1, calls.get(), "The provider failure must not be retried automatically");
        verifyNoInteractions(meals);
        // A failed first request releases its session slot; capacity one still permits a new demo session.
        config.setApiKey(""); config.setMaxSessions(1);
        when(meals.selectList(any())).thenReturn(List.of());
        var next = service.chat("alice", new ChatRequest("如何控盐", null));
        assertEquals(2, memory.view("alice", next.sessionId()).messages().size());
    }

    private AgentConfig config() {
        AgentConfig config = new AgentConfig();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setModel("test-integration-model");
        config.setApiKey("local-integration-test-key");
        return config;
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", handler);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
    }

    private byte[] toolResponse() throws java.io.IOException {
        ObjectNode message = json.createObjectNode().put("role", "assistant");
        message.putNull("content");
        ArrayNode calls = message.putArray("tool_calls");
        addCall(calls, "meal_call", "summarize_meals",
                "{\"startDate\":\"2026-10-24\",\"endDate\":\"2026-10-30\"}");
        addCall(calls, "knowledge_call", "search_knowledge", "{\"query\":\"控盐 高血压\",\"topK\":1}");
        return completion(message, "tool_calls", 100, 20);
    }

    private byte[] finalResponse(String text) throws java.io.IOException {
        return completion(json.createObjectNode().put("role", "assistant").put("content", text), "stop", 150, 30);
    }

    private byte[] completion(ObjectNode message, String finishReason, int inputTokens, int outputTokens) throws java.io.IOException {
        ObjectNode response = json.createObjectNode().put("id", "local-completion").put("object", "chat.completion")
                .put("created", 1).put("model", "test-integration-model");
        ObjectNode choice = response.putArray("choices").addObject().put("index", 0).put("finish_reason", finishReason);
        choice.set("message", message);
        response.putObject("usage").put("prompt_tokens", inputTokens).put("completion_tokens", outputTokens)
                .put("total_tokens", inputTokens + outputTokens);
        return json.writeValueAsBytes(response);
    }

    private static void addCall(ArrayNode calls, String id, String name, String arguments) {
        calls.addObject().put("id", id).put("type", "function").putObject("function")
                .put("name", name).put("arguments", arguments);
    }

    private static MealRecord meal(long id, Integer calories) {
        MealRecord meal = new MealRecord();
        meal.setId(id); meal.setUserId(7L); meal.setRecordDate(LocalDate.of(2026, 10, 29)); meal.setCalories(calories);
        return meal;
    }
}
