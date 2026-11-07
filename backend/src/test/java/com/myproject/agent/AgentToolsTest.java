package com.myproject.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myproject.entity.MealRecord;
import com.myproject.mapper.MealRecordMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentToolsTest {
    private final ObjectMapper json = new ObjectMapper();
    private MealRecordMapper meals;
    private AgentTools tools;

    @BeforeEach void setup() throws Exception {
        meals = mock(MealRecordMapper.class);
        tools = new AgentTools(meals, new KnowledgeSearch(json), json,
                Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test void frameworkToolContractExposesOnlyBusinessArgumentsAndPreservesRequiredFields() {
        var definitions = tools.definitions();
        assertEquals(List.of("get_meals", "summarize_meals", "search_knowledge"),
                definitions.stream().map(ToolSpecification::name).toList());
        var expectedFields = Map.of("get_meals", Set.of("date"),
                "summarize_meals", Set.of("startDate", "endDate"),
                "search_knowledge", Set.of("query", "topK"));
        var expectedRequired = Map.of("get_meals", Set.of("date"),
                "summarize_meals", Set.of("startDate", "endDate"),
                "search_knowledge", Set.of("query"));
        for (var definition : definitions) {
            var parameters = definition.parameters();
            assertEquals(expectedFields.get(definition.name()), parameters.properties().keySet());
            assertEquals(expectedRequired.get(definition.name()), Set.copyOf(parameters.required()));
            assertEquals(Boolean.FALSE, parameters.additionalProperties());
            assertFalse(parameters.properties().containsKey("userId"));
            parameters.properties().forEach((name, schema) -> {
                if ("topK".equals(name)) assertInstanceOf(JsonIntegerSchema.class, schema);
                else assertInstanceOf(JsonStringSchema.class, schema);
            });
        }
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"}) void mealQueryAlwaysIncludesServerUserAndCountsMissingCalories() throws Exception {
        MealRecord known = meal(1L, 350), missing = meal(2L, null);
        when(meals.selectList(any())).thenReturn(List.of(known, missing));
        var result = tools.execute("get_meals", json.readTree("{\"date\":\"2026-10-29\"}"), 7L);
        assertEquals(350, result.data().path("recordedCalories").asInt());
        assertEquals(1, result.data().path("missingCaloriesCount").asInt());
        assertTrue(result.data().path("records").get(1).path("calories").isNull());
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(meals).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("user_id"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(7L));
    }

    @Test void rejectsIdentityInjectionUnknownToolsAndInvalidDatesBeforeDatabase() throws Exception {
        for (String arguments : List.of("{\"date\":\"2026-10-29\",\"userId\":999}",
                "{\"date\":\"2026-02-30\"}", "{\"date\":\"2026-10-31\"}", "{\"date\":\"2026-10-29 OR 1=1\"}")) {
            var input = json.readTree(arguments);
            assertThrows(IllegalArgumentException.class, () -> tools.execute("get_meals", input, 7L));
        }
        assertThrows(IllegalArgumentException.class, () -> tools.execute("run_sql", json.createObjectNode(), 7L));
        verifyNoInteractions(meals);
    }

    @Test void rejectsWideOrReversedDateRange() throws Exception {
        var wide = json.readTree("{\"startDate\":\"2026-09-01\",\"endDate\":\"2026-10-29\"}");
        var reversed = json.readTree("{\"startDate\":\"2026-10-29\",\"endDate\":\"2026-10-01\"}");
        assertThrows(IllegalArgumentException.class, () -> tools.execute("summarize_meals", wide, 7L));
        assertThrows(IllegalArgumentException.class, () -> tools.execute("summarize_meals", reversed, 7L));
        verifyNoInteractions(meals);
    }

    @Test void retrievalReturnsMatchingGroundedSourcesAndRejectsOverflow() throws Exception {
        var result = tools.execute("search_knowledge", json.readTree("{\"query\":\"控盐 高血压\",\"topK\":1}"), 7L);
        assertEquals("WHO-SODIUM", result.sources().get(0).id());
        assertTrue(result.sources().get(0).url().startsWith("https://www.who.int/"));
        assertTrue(tools.execute("search_knowledge", json.readTree("{\"query\":\"zzzznotfound\"}"), 7L).sources().isEmpty());
        // Provider-side schema support varies; execute must enforce bounds and types itself.
        for (String topK : List.of("0", "4", "1.5", "\"2\"", "4294967297")) {
            var invalid = json.readTree("{\"query\":\"盐\",\"topK\":" + topK + "}");
            assertThrows(IllegalArgumentException.class, () -> tools.execute("search_knowledge", invalid, 7L));
        }
    }

    @Test void truncatesRecordsExplicitly() throws Exception {
        when(meals.selectList(any())).thenReturn(java.util.stream.LongStream.range(1, 202).mapToObj(id -> meal(id, 100)).toList());
        var result = tools.execute("get_meals", json.readTree("{\"date\":\"2026-10-29\"}"), 7L);
        assertEquals(200, result.data().path("recordCount").asInt());
        assertTrue(result.data().path("truncated").asBoolean());
        assertTrue(result.data().path("detailsTruncated").asBoolean());
        assertEquals(30, result.data().path("records").size());
    }

    private static MealRecord meal(Long id, Integer calories) {
        MealRecord meal = new MealRecord(); meal.setId(id); meal.setUserId(7L);
        meal.setRecordDate(LocalDate.of(2026, 10, 29)); meal.setCalories(calories); meal.setDescription("午饭");
        return meal;
    }
}
