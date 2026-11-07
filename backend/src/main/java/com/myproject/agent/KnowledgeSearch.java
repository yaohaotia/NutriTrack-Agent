package com.myproject.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import static com.myproject.agent.AgentModels.Source;

/** Small curated corpus, ranked with BM25 over Chinese bigrams and Latin word tokens. */
@Component
public class KnowledgeSearch {
    private com.myproject.rag.RagService rag;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRagService(com.myproject.rag.RagService rag) { this.rag = rag; }
    public record Entry(String id, String title, String url, String excerpt, List<String> keywords) {}
    private final List<Entry> entries;
    private final List<Map<String, Long>> terms;
    private final Map<String, Integer> frequencies = new HashMap<>();
    private final double averageLength;

    public KnowledgeSearch(ObjectMapper mapper) throws IOException {
        try (var stream = new ClassPathResource("agent-knowledge.json").getInputStream()) {
            entries = List.copyOf(mapper.readValue(stream, new TypeReference<List<Entry>>() {}));
        }
        Set<String> ids = new HashSet<>();
        for (Entry entry : entries) {
            if (!ids.add(entry.id()) || !entry.url().startsWith("https://")) {
                throw new IllegalArgumentException("Invalid curated knowledge entry");
            }
        }
        terms = entries.stream().map(e -> tokens(e.title() + " " + e.excerpt() + " " + String.join(" ", e.keywords()))
                .stream().collect(Collectors.groupingBy(t -> t, Collectors.counting()))).toList();
        for (var document : terms) document.keySet().forEach(term -> frequencies.merge(term, 1, Integer::sum));
        averageLength = terms.stream().mapToLong(m -> m.values().stream().mapToLong(Long::longValue).sum())
                .average().orElse(1);
    }

    public List<Source> search(String query, int topK) {
        Set<String> queryTerms = new HashSet<>(tokens(query));
        record Hit(int index, double score) {}
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            var document = terms.get(i);
            long length = document.values().stream().mapToLong(Long::longValue).sum();
            double score = 0;
            for (String term : queryTerms) {
                long count = document.getOrDefault(term, 0L);
                if (count == 0) continue;
                double idf = Math.log(1 + (entries.size() - frequencies.get(term) + 0.5) / (frequencies.get(term) + 0.5));
                score += idf * count * 2.2 / (count + 1.2 * (0.25 + 0.75 * length / averageLength));
            }
            if (score > 0) hits.add(new Hit(i, score));
        }
        return hits.stream().sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparingInt(Hit::index))
                .limit(topK).map(hit -> entries.get(hit.index()))
                .map(e -> new Source(e.id(), e.title(), e.url(), e.excerpt())).toList();
    }

    public List<Source> search(String query, int topK, Long userId) {
        if (rag == null) return search(query, topK); // Lightweight legacy fixtures; application wiring always provides RAG.
        return rag.search(userId, query, com.myproject.rag.RagModels.Mode.hybrid, topK).hits().stream()
                .map(hit -> new Source(hit.id(), hit.title(), hit.sourceUrl(), hit.excerpt(), hit.documentId(), hit.page(), hit.chunkId(), hit.documentVersion()))
                .toList();
    }

    private static List<String> tokens(String text) {
        List<String> result = new ArrayList<>();
        var matcher = java.util.regex.Pattern.compile("[\\p{IsHan}]+|[a-z0-9]+")
                .matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.codePoints().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                int[] points = word.codePoints().toArray();
                if (points.length == 1) result.add(word);
                for (int i = 0; i < points.length - 1; i++) result.add(new String(points, i, 2));
            } else result.add(word);
        }
        return result;
    }
}
