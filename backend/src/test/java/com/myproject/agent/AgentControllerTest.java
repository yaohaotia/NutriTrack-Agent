package com.myproject.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentControllerTest {
    private MockMvc mvc;
    private AgentService service;

    @BeforeEach void setup() {
        service = mock(AgentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AgentController(service, new AgentMemory(new AgentConfig())))
                .setControllerAdvice(new AgentExceptionHandler()).build();
    }

    @Test void unauthenticatedAccessReturns401() throws Exception {
        mvc.perform(get("/api/agent/status")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(service);
    }

    @Test void identityComesOnlyFromPrincipalDespiteUntrustedBodyAndHeader() throws Exception {
        mvc.perform(post("/api/agent/chat").principal(() -> "alice").header("X-Username", "bob")
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\",\"username\":\"bob\",\"userId\":999}"))
                .andExpect(status().isOk());
        verify(service).chat(eq("alice"), argThat(request -> "hello".equals(request.message())));
    }

    @Test void unknownSessionReturns404WithoutLeakingOwner() throws Exception {
        mvc.perform(get("/api/agent/sessions/unknown").principal(() -> "alice"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
    }

    @Test void malformedJsonReturns400() throws Exception {
        mvc.perform(post("/api/agent/chat").principal(() -> "alice").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(service);
    }
}
