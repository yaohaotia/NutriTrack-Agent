package com.myproject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myproject.agent.*;
import com.myproject.config.JwtAuthenticationFilter;
import com.myproject.config.SecurityConfig;
import com.myproject.controller.AIAnalysisController;
import com.myproject.entity.User;
import com.myproject.mapper.MealRecordMapper;
import com.myproject.mapper.UserMapper;
import com.myproject.service.AIAnalysisService;
import com.myproject.utils.JwtUtil;
import com.myproject.vo.ChatMessageVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Exercises real filters, controllers, Agent, tools and memory; database access alone is mocked. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = HealthAgentApiTest.App.class)
@TestPropertySource(properties = {"jwt.secret=test-only-private-key-test-only-private-key-test-only-private-key-test-only-private-key", "jwt.expiration=3600000"})
class HealthAgentApiTest {
    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, AgentController.class,
            AgentExceptionHandler.class, AgentService.class, AgentMemory.class, AgentTools.class,
            KnowledgeSearch.class, AgentModelClient.class, AIAnalysisController.class})
    static class App {
        @Bean AgentConfig config() { return new AgentConfig(); }
        @Bean ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean MealRecordMapper meals() { return mock(MealRecordMapper.class); }
        @Bean UserMapper users() { return mock(UserMapper.class); }
        @Bean AIAnalysisService legacy() { return mock(AIAnalysisService.class); }
        @Bean UserDetailsService userDetails() {
            return username -> org.springframework.security.core.userdetails.User
                    .withUsername(username).password("unused").roles("USER").build();
        }
        @Bean JwtUtil jwt() {
            JwtUtil jwt = new JwtUtil();
            ReflectionTestUtils.setField(jwt, "secret", "test-only-private-key-".repeat(4));
            ReflectionTestUtils.setField(jwt, "expiration", 3600000L);
            return jwt;
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired JwtUtil jwt;
    @Autowired UserMapper users;
    @Autowired MealRecordMapper meals;
    @Autowired AIAnalysisService legacy;
    @Autowired ObjectMapper json;
    MockMvc mvc;
    String alice;
    String bob;

    @BeforeEach void setUp() {
        reset(users, meals, legacy);
        User a = new User(); a.setId(1L); a.setUsername("alice");
        User b = new User(); b.setId(2L); b.setUsername("bob");
        when(users.selectByUsername("alice")).thenReturn(a);
        when(users.selectByUsername("bob")).thenReturn(b);
        when(meals.selectList(any())).thenReturn(List.of());
        alice = "Bearer " + jwt.generateToken(1L, "alice");
        bob = "Bearer " + jwt.generateToken(2L, "bob");
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void anonymousCannotReachNewOrLegacyAi() throws Exception {
        for (String path : List.of("/api/agent/status", "/api/ai/trend", "/ai/suggestions", "/api/knowledge/status", "/api/knowledge/documents")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));
        }
        verifyNoInteractions(meals, users, legacy);
    }

    @Test void staleSignatureAndExpiredTokensReturnUnauthorizedBeforeBusinessQueries() throws Exception {
        JwtUtil oldServer = new JwtUtil();
        ReflectionTestUtils.setField(oldServer, "secret", "previous-server-private-key-".repeat(4));
        ReflectionTestUtils.setField(oldServer, "expiration", 3600000L);
        JwtUtil expired = new JwtUtil();
        ReflectionTestUtils.setField(expired, "secret", "test-only-private-key-".repeat(4));
        ReflectionTestUtils.setField(expired, "expiration", -1000L);
        for (String token : List.of(oldServer.generateToken(1L, "alice"), expired.generateToken(1L, "alice"))) {
            for (String path : List.of("/meal-records/query_me", "/api/agent/status")) {
                mvc.perform(get(path).header("Authorization", "Bearer " + token))
                        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
            }
        }
        verifyNoInteractions(meals, users, legacy);
    }

    @Test void actualJwtRunsDemoAndEnforcesSessionOwnershipOnReadContinueAndDelete() throws Exception {
        String body = mvc.perform(post("/api/agent/chat").header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"如何减少盐的摄入\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("demo"))
                .andExpect(jsonPath("$.data.usage.modelCalls").value(0))
                .andExpect(jsonPath("$.data.steps[0].status").value("success"))
                .andExpect(jsonPath("$.data.sources").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).path("data").path("sessionId").asText();
        mvc.perform(get("/api/agent/sessions/" + id).header("Authorization", alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.messages.length()").value(2));
        mvc.perform(get("/api/agent/sessions/" + id).header("Authorization", bob)).andExpect(status().isNotFound());
        mvc.perform(post("/api/agent/chat").header("Authorization", bob).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("message", "继续", "sessionId", id))))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/agent/sessions/" + id).header("Authorization", bob)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/agent/sessions/" + id).header("Authorization", alice)).andExpect(status().isOk());
        mvc.perform(get("/api/agent/sessions/" + id).header("Authorization", alice)).andExpect(status().isNotFound());
    }

    @Test void legacyChatIgnoresForgedBodyAndHeaderIdentity() throws Exception {
        ChatMessageVO reply = new ChatMessageVO(); reply.setContent("ok");
        when(legacy.chatWithAI(null, "alice", "hello")).thenReturn(reply);
        mvc.perform(post("/api/ai/chat").header("Authorization", alice).header("X-Username", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hello\",\"username\":\"bob\",\"userId\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value("ok"));
        verify(legacy).chatWithAI(null, "alice", "hello");
    }

    @Test void malformedAgentInputIsControlledAndNoToolsRun() throws Exception {
        mvc.perform(post("/api/agent/chat").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\" \"}" )).andExpect(status().isBadRequest());
        mvc.perform(post("/api/agent/chat").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                .content("{" )).andExpect(status().isBadRequest());
        verifyNoInteractions(meals, users);
    }
}
