package com.myproject.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myproject.agent.*;
import com.myproject.entity.User;
import com.myproject.mapper.MealRecordMapper;
import com.myproject.mapper.UserMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagAgentFlowTest {
    @TempDir Path directory;
    @Test void agentSearchesOwnedDocumentsAndReturnsExactCitationLocationsWithoutRequiringMeals() throws Exception {
        ObjectMapper json = new ObjectMapper(); var config = new RagConfig(); config.setDataDir(directory.toString());
        RagEmbedding embedding = new RagEmbedding() {
            public String modelId() { return "fixture"; } public int dimension() { return 2; }
            public float[] embed(String s) { return new float[]{1, .1f}; }
        };
        var rag = new RagService(config, embedding, new RagDocumentParser(), json);
        String doc = rag.upload(7L, null, "私人文档", "", "private.md", "# 暗号\n\nnutriomega是本文档的测试暗号。".getBytes(StandardCharsets.UTF_8)).document().id();
        var knowledge = new KnowledgeSearch(json); knowledge.setRagService(rag);
        var meals = mock(MealRecordMapper.class); var users = mock(UserMapper.class);
        User user = new User(); user.setUsername("alice"); user.setId(7L); when(users.selectByUsername("alice")).thenReturn(user);
        var model = mock(AgentModelClient.class); var agent = new AgentConfig(); agent.setApiKey("test-only");
        var tool = ToolExecutionRequest.builder().id("rag-call").name("search_knowledge").arguments("{\"query\":\"nutriomega\"}").build();
        when(model.complete(any(), any(), anyLong())).thenReturn(new AgentModelClient.Completion(AiMessage.from(tool), 10, 5),
                new AgentModelClient.Completion(AiMessage.from("文档中的暗号是nutriomega [" + doc + "-1]"), 20, 10));
        var service = new AgentService(agent, new AgentMemory(agent), new AgentTools(meals, knowledge, json), model, users, json);
        var response = service.chat("alice", new AgentModels.ChatRequest("我上传的文档里的暗号是什么", null));
        assertTrue(response.answer().contains("nutriomega"));
        var source = response.sources().stream().filter(s -> doc.equals(s.documentId())).findFirst().orElseThrow();
        assertEquals(1, source.page()); assertEquals(doc + "-1", source.chunkId()); assertEquals(1, source.documentVersion());
        verifyNoInteractions(meals);
        assertTrue(knowledge.search("nutriomega", 3, 8L).stream().noneMatch(s -> doc.equals(s.documentId())));
    }
}
