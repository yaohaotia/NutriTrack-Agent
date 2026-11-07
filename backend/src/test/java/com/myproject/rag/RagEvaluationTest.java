package com.myproject.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static com.myproject.rag.RagModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** Small authored diagnostic set, not an independent benchmark or an answer-quality evaluation. */
class RagEvaluationTest {
    @TempDir Path directory;
    record Question(String query, String document) {}
    @Test void evaluatesRealChineseEmbeddingsAgainstBm25WithoutInventingImprovement() throws Exception {
        ObjectMapper json = new ObjectMapper(); RagConfig config = new RagConfig(); config.setDataDir(directory.toString());
        var model = new LocalRagEmbedding();
        float[] oats = model.embed("燕麦和全谷物富含膳食纤维");
        assertEquals(512, oats.length);
        assertTrue(RagRetrieval.cosine(oats, model.embed("燕麦和全谷物富含膳食纤维")) > .99);
        RagService rag = new RagService(config, model, new RagDocumentParser(), json);
        List<Question> questions;
        try (var input = getClass().getResourceAsStream("/rag-evaluation.json")) { questions = List.of(json.readValue(input, Question[].class)); }
        List<Map<String, Object>> measurements = new ArrayList<>();
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            int positive = 0, found = 0, negatives = 0, falsePositive = 0; double reciprocal = 0; long millis = 0;
            for (Question question : questions) {
                var result = rag.search(7L, question.query(), mode, 3); millis += result.durationMs();
                int rank = 0;
                for (int i = 0; i < result.hits().size(); i++) if (Objects.equals(question.document(), result.hits().get(i).documentId())) { rank = i + 1; break; }
                if (question.document() == null) { negatives++; if (!result.hits().isEmpty()) falsePositive++; }
                else { positive++; if (rank > 0) { found++; reciprocal += 1.0 / rank; } }
                Map<String, Object> row = new LinkedHashMap<>(); row.put("mode", mode); row.put("query", question.query()); row.put("expectedDocument", question.document());
                row.put("rank", rank); row.put("documents", result.hits().stream().map(Hit::documentId).toList()); row.put("durationMs", result.durationMs()); measurements.add(row);
            }
            summaries.add(Map.of("mode", mode, "answerableQuestions", positive, "hitRateAt3", (double)found/positive,
                    "mrrAt3", reciprocal/positive, "negativeQuestions", negatives, "falsePositiveRate", (double)falsePositive/negatives, "meanRetrievalMs", (double)millis/questions.size()));
        }
        Path report = Path.of("target/rag-evaluation.json"); Files.createDirectories(report.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of("model", model.modelId(), "dimension", 512,
                "corpusDocuments", rag.status(7L).documents(), "corpusChunks", rag.status(7L).chunks(), "questions", questions.size(),
                "scope", "Authored retrieval diagnostic; not independent or generation accuracy", "summary", summaries, "details", measurements));
        assertEquals(3, summaries.size());
        assertTrue(rag.search(7L, "酱油中的钠", Mode.hybrid, 3).hits().stream().anyMatch(h -> h.documentId().equals("WHO-SODIUM")));
        RagService restarted = new RagService(config, model, new RagDocumentParser(), json);
        assertEquals(rag.status(7L).chunks(), restarted.status(7L).chunks());
    }
}
