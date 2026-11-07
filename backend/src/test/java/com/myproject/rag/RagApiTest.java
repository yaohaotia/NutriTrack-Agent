package com.myproject.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myproject.agent.AgentExceptionHandler;
import com.myproject.entity.User;
import com.myproject.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RagApiTest {
    @TempDir Path directory;
    MockMvc mvc; ObjectMapper json = new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        var config = new RagConfig(); config.setDataDir(directory.toString());
        RagEmbedding model = new RagEmbedding() {
            public String modelId() { return "test-model"; } public int dimension() { return 2; }
            public float[] embed(String s) { return new float[]{1,1}; }
        };
        var users = mock(UserMapper.class);
        var alice = new User(); alice.setId(7L); alice.setUsername("alice");
        var bob = new User(); bob.setId(8L); bob.setUsername("bob");
        when(users.selectByUsername("alice")).thenReturn(alice); when(users.selectByUsername("bob")).thenReturn(bob);
        mvc = standaloneSetup(new RagController(new RagService(config, model, new RagDocumentParser(), json), users))
                .setControllerAdvice(new AgentExceptionHandler()).build();
    }
    @Test void rejectsMissingIdentityAndMalformedRequests() throws Exception {
        mvc.perform(get("/api/knowledge/documents")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/knowledge/search").principal(() -> "alice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"盐\",\"mode\":\"unknown\"}")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/knowledge/documents").principal(() -> "alice")).andExpect(status().isBadRequest());
    }
    @Test void usesPrincipalForUploadsSearchAndCrudAndNeverReturnsVectors() throws Exception {
        var file = new MockMultipartFile("file", "notes.md", "text/markdown", "# 私人\n\n食物私人词secretword".getBytes(StandardCharsets.UTF_8));
        var response = mvc.perform(multipart("/api/knowledge/documents").file(file).param("userId", "8").principal(() -> "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.duplicate").value(false)).andReturn();
        String id = json.readTree(response.getResponse().getContentAsString()).path("data").path("document").path("id").asText();
        mvc.perform(get("/api/knowledge/documents/" + id).principal(() -> "alice")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chunks[0].text").exists()).andExpect(jsonPath("$.data.chunks[0].vector").doesNotExist());
        mvc.perform(get("/api/knowledge/documents/" + id).param("userId", "7").principal(() -> "bob")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/knowledge/documents/" + id).principal(() -> "bob")).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/knowledge/documents/" + id).file(file).with(r -> { r.setMethod("PUT"); return r; }).principal(() -> "bob")).andExpect(status().isNotFound());
        mvc.perform(post("/api/knowledge/search").principal(() -> "bob").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"secretword\",\"mode\":\"bm25\",\"userId\":7}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hits").isEmpty());
        mvc.perform(delete("/api/knowledge/documents/WHO-SODIUM").principal(() -> "alice")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/knowledge/documents/" + id).principal(() -> "alice")).andExpect(status().isOk());
        mvc.perform(get("/api/knowledge/documents/" + id).principal(() -> "alice")).andExpect(status().isNotFound());
    }
}
