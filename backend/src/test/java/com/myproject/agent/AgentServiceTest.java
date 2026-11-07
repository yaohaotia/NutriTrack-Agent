package com.myproject.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import com.myproject.entity.User;
import com.myproject.mapper.MealRecordMapper;
import com.myproject.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.ArrayList;
import static com.myproject.agent.AgentModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private AgentConfig config;
    private AgentMemory memory;
    private AgentModelClient model;
    private UserMapper users;
    private AgentService service;

    @BeforeEach void setup() throws Exception {
        config = new AgentConfig(); memory = new AgentMemory(config); model = mock(AgentModelClient.class); users = mock(UserMapper.class);
        User alice = new User(); alice.setId(7L); alice.setUsername("alice"); when(users.selectByUsername("alice")).thenReturn(alice);
        MealRecordMapper meals = mock(MealRecordMapper.class); when(meals.selectList(any())).thenReturn(List.of());
        AgentTools tools = new AgentTools(meals, new KnowledgeSearch(json), json);
        service = new AgentService(config, memory, tools, model, users, json);
    }

    @Test void demoUsesRealToolsWithoutCallingModelAndRetainsConversation() {
        ChatResponse response = service.chat("alice", new ChatRequest("最近7天如何控盐", null));
        assertEquals("demo", response.mode()); assertEquals(2, response.steps().size());
        assertEquals("summarize_meals", response.steps().get(0).tool());
        assertTrue(response.answer().contains("没有记录不代表没有进食"));
        assertEquals(0, response.usage().modelCalls()); verifyNoInteractions(model);
        assertEquals(2, memory.view("alice", response.sessionId()).messages().size());
    }

    @Test void liveRunsStructuredToolLoopAndFeedsObservationBack() throws Exception {
        config.setApiKey("test-key");
        var call = json.readTree("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"search_knowledge\",\"arguments\":\"{\\\"query\\\":\\\"控盐\\\"}\"}}]}");
        var answer = json.readTree("{\"role\":\"assistant\",\"content\":\"请留意酱料里的钠 [WHO-SODIUM]\"}");
        when(model.complete(any(), any(), anyLong())).thenReturn(completion(call, 100, 20), completion(answer, 150, 30));
        ChatResponse response = service.chat("alice", new ChatRequest("怎么控盐", null));
        assertEquals("live", response.mode()); assertEquals(2, response.usage().modelCalls());
        assertEquals(250, response.usage().promptTokens()); assertEquals(50, response.usage().completionTokens());
        assertEquals("WHO-SODIUM", response.sources().get(0).id());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(model, times(2)).complete(messages.capture(), any(), anyLong());
        ToolExecutionResultMessage result = assertInstanceOf(ToolExecutionResultMessage.class, messages.getValue().get(3));
        assertEquals("call1", result.id());
        assertEquals("search_knowledge", result.toolName());
    }

    @Test void enforcesIterationLimitAndBlocksRepeatedActions() throws Exception {
        config.setApiKey("test"); config.setMaxIterations(2);
        var call1 = json.readTree("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"search_knowledge\",\"arguments\":\"{\\\"query\\\":\\\"控盐\\\"}\"}}]}");
        var call2 = call1.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)call2.path("tool_calls").get(0)).put("id", "b");
        when(model.complete(any(), any(), anyLong())).thenReturn(completion(call1, 0, 0), completion(call2, 0, 0));
        ChatResponse response = service.chat("alice", new ChatRequest("控盐", null));
        assertEquals(2, response.usage().modelCalls()); assertTrue(response.answer().contains("调用上限"));
        assertEquals("error", response.steps().get(1).status()); assertEquals("已阻止重复工具调用", response.steps().get(1).summary());
    }

    @Test void rejectsUngroundedPersonalAnalysis() throws Exception {
        config.setApiKey("test");
        when(model.complete(any(), any(), anyLong())).thenReturn(completion(json.readTree("{\"role\":\"assistant\",\"content\":\"你昨天吃了900千卡\"}"), 0, 0));
        ChatResponse response = service.chat("alice", new ChatRequest("我昨天吃得怎么样", null));
        assertFalse(response.answer().contains("900")); assertTrue(response.answer().contains("未取得有效工具证据"));
    }

    @Test void refusesUnsupportedAnswerWhenRetrievalSucceededButFoundNoDocuments() throws Exception {
        config.setApiKey("test");
        var call = json.readTree("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"empty\",\"type\":\"function\",\"function\":{\"name\":\"search_knowledge\",\"arguments\":\"{\\\"query\\\":\\\"zzzxxyy\\\"}\"}}]}");
        when(model.complete(any(), any(), anyLong())).thenReturn(completion(call, 0, 0),
                new AgentModelClient.Completion(AiMessage.from("这是没有依据的结论"), 0, 0));
        var result = service.chat("alice", new ChatRequest("zzzxxyy", null));
        assertTrue(result.answer().contains("不足以支持回答")); assertFalse(result.answer().contains("没有依据的结论"));
    }

    @Test void rejectsPersonalAnalysisGroundedOnlyInGenericKnowledge() throws Exception {
        config.setApiKey("test");
        var call = json.readTree("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"search_knowledge\",\"arguments\":\"{\\\"query\\\":\\\"控盐\\\"}\"}}]}");
        var answer = json.readTree("{\"role\":\"assistant\",\"content\":\"你昨天吃了900千卡\"}");
        when(model.complete(any(), any(), anyLong())).thenReturn(completion(call, 0, 0), completion(answer, 0, 0));
        ChatResponse response = service.chat("alice", new ChatRequest("我昨天吃得怎么样", null));
        assertFalse(response.answer().contains("900")); assertTrue(response.answer().contains("未核对你的饮食记录"));
    }

    @Test void replacesUnretrievedCitationIds() throws Exception {
        config.setApiKey("test");
        var call = json.readTree("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"search_knowledge\",\"arguments\":\"{\\\"query\\\":\\\"控盐\\\",\\\"topK\\\":1}\"}}]}");
        var answer = json.readTree("{\"role\":\"assistant\",\"content\":\"选择低钠食品 [WHO-SODIUM]；其他结论 [WHO-FAKE]\"}");
        when(model.complete(any(), any(), anyLong())).thenReturn(completion(call, 0, 0), completion(answer, 0, 0));
        ChatResponse response = service.chat("alice", new ChatRequest("控盐", null));
        assertTrue(response.answer().contains("[WHO-SODIUM]")); assertFalse(response.answer().contains("WHO-FAKE"));
        assertTrue(response.answer().contains("[来源未核验]"));
    }

    @Test void modelFailureDoesNotPersistIncompleteTurnAndReleasesSession() {
        ChatResponse initial = service.chat("alice", new ChatRequest("控盐", null));
        config.setApiKey("test");
        when(model.complete(any(), any(), anyLong())).thenThrow(new AgentException(502, "模型暂不可用"));
        assertThrows(AgentException.class, () -> service.chat("alice", new ChatRequest("接着说", initial.sessionId())));
        assertEquals(2, memory.view("alice", initial.sessionId()).messages().size());
        config.setApiKey("");
        assertNotNull(service.chat("alice", new ChatRequest("控盐", initial.sessionId())));
    }

    @Test void enforcesDemoToolBudget() {
        config.setMaxToolCalls(1);
        ChatResponse response = service.chat("alice", new ChatRequest("健康饮食", null));
        assertEquals(1, response.usage().toolCalls()); assertTrue(response.answer().contains("调用上限"));
    }

    @Test void rejectsOversizedQuestionsBeforeResolvingIdentity() {
        assertEquals(400, assertThrows(AgentException.class, () -> service.chat("alice", new ChatRequest("a".repeat(2001), null))).status());
        verifyNoInteractions(users, model);
    }

    @Test void convertsCompletedHistoryToFrameworkMessagesAndKeepsUserIdentityOutOfToolArguments() {
        ChatResponse initial = service.chat("alice", new ChatRequest("怎么控盐", null));
        config.setApiKey("test-key");
        when(model.complete(any(), any(), anyLong())).thenReturn(
                new AgentModelClient.Completion(AiMessage.from("请参考资料"), 0, 0));
        service.chat("alice", new ChatRequest("再说一下", initial.sessionId()));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(model).complete(messages.capture(), any(), anyLong());
        assertEquals(4, messages.getValue().size());
        assertEquals("怎么控盐", assertInstanceOf(UserMessage.class, messages.getValue().get(1)).singleText());
        assertInstanceOf(AiMessage.class, messages.getValue().get(2));
        assertEquals("再说一下", assertInstanceOf(UserMessage.class, messages.getValue().get(3)).singleText());
        assertEquals("LangChain4j", service.status().limits().get("framework"));
    }

    private AgentModelClient.Completion completion(JsonNode message, long inputTokens, long outputTokens) {
        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            requests.add(ToolExecutionRequest.builder().id(call.path("id").asText())
                    .name(call.path("function").path("name").asText())
                    .arguments(call.path("function").path("arguments").asText()).build());
        }
        String text = message.path("content").isTextual() ? message.path("content").asText() : null;
        AiMessage ai = requests.isEmpty() ? AiMessage.from(text) : AiMessage.from(text, requests);
        return new AgentModelClient.Completion(ai, inputTokens, outputTokens);
    }
}
