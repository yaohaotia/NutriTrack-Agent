package com.myproject.rag;

import java.util.*;
import java.util.stream.Collectors;
import static com.myproject.rag.RagModels.*;

/** Exact cosine search and BM25 over the same authorized candidate set; reciprocal rank fusion. */
public final class RagRetrieval {
    private record Candidate(Document document, Chunk chunk, double lexical, double semantic) {}
    private static final Set<String> STOP = Set.of("什么", "如何", "怎么", "哪些", "可以", "是否", "一个", "我的", "一下", "为什么");
    private RagRetrieval() {}

    public static List<Hit> search(List<Document> visible, String query, float[] queryVector,
                                   Mode mode, int topK, double threshold, double minLexicalCoverage) {
        List<Map<String, Long>> terms = new ArrayList<>();
        List<Document> docs = new ArrayList<>();
        List<Chunk> chunks = new ArrayList<>();
        Map<String, Integer> frequencies = new HashMap<>();
        for (Document doc : visible) for (Chunk chunk : doc.chunks()) {
            var term = tokens(doc.title() + " " + chunk.section() + " " + chunk.text()).stream()
                    .collect(Collectors.groupingBy(t -> t, Collectors.counting()));
            terms.add(term); docs.add(doc); chunks.add(chunk);
            term.keySet().forEach(t -> frequencies.merge(t, 1, Integer::sum));
        }
        double avg = terms.stream().mapToLong(t -> t.values().stream().mapToLong(Long::longValue).sum()).average().orElse(1);
        Set<String> queryTerms = new HashSet<>(tokens(query));
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Long> document = terms.get(i);
            long length = document.values().stream().mapToLong(Long::longValue).sum();
            double bm25 = 0; int matchedTerms = 0;
            for (String term : queryTerms) {
                long tf = document.getOrDefault(term, 0L);
                if (tf > 0) {
                    matchedTerms++;
                    double idf = Math.log(1 + (chunks.size() - frequencies.get(term) + 0.5) / (frequencies.get(term) + 0.5));
                    bm25 += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * length / Math.max(1, avg)));
                }
            }
            if (!queryTerms.isEmpty() && (double)matchedTerms / queryTerms.size() < minLexicalCoverage) bm25 = 0;
            candidates.add(new Candidate(docs.get(i), chunks.get(i), bm25,
                    queryVector == null ? 0 : cosine(queryVector, chunks.get(i).vector())));
        }
        Comparator<Candidate> tie = Comparator.comparing(c -> c.chunk().id());
        var lexical = candidates.stream().filter(c -> c.lexical() > 0)
                .sorted(Comparator.comparingDouble(Candidate::lexical).reversed().thenComparing(tie)).limit(20).toList();
        var semantic = candidates.stream().filter(c -> queryVector != null && c.semantic() >= threshold)
                .sorted(Comparator.comparingDouble(Candidate::semantic).reversed().thenComparing(tie)).limit(20).toList();
        Map<String, Double> scores = new HashMap<>();
        if (mode != Mode.vector) addRanks(lexical, scores);
        if (mode != Mode.bm25) addRanks(semantic, scores);
        return candidates.stream().filter(c -> scores.containsKey(c.chunk().id()))
                .sorted(Comparator.<Candidate>comparingDouble(c -> scores.get(c.chunk().id())).reversed().thenComparing(tie))
                .limit(topK).map(c -> new Hit(c.chunk().id(), c.document().id(), c.document().title(), c.document().sourceUrl(),
                        c.chunk().id(), c.chunk().page(), c.chunk().section(), c.chunk().text(), scores.get(c.chunk().id()),
                        queryVector == null ? null : c.semantic(), c.lexical(), c.document().version())).toList();
    }

    private static void addRanks(List<Candidate> list, Map<String, Double> scores) {
        for (int i = 0; i < list.size(); i++) scores.merge(list.get(i).chunk().id(), 1.0 / (60 + i + 1), Double::sum);
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) throw new IllegalArgumentException("Embedding dimensions differ");
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) { dot += (double)a[i] * b[i]; aa += (double)a[i] * a[i]; bb += (double)b[i] * b[i]; }
        return aa == 0 || bb == 0 ? 0 : dot / Math.sqrt(aa * bb);
    }

    private static List<String> tokens(String text) {
        List<String> result = new ArrayList<>();
        var matcher = java.util.regex.Pattern.compile("[\\p{IsHan}]+|[a-z0-9]+").matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.codePoints().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                int[] chars = word.codePoints().toArray();
                if (chars.length == 1 && !STOP.contains(word)) result.add(word);
                for (int i = 0; i < chars.length - 1; i++) {
                    String term = new String(chars, i, 2);
                    if (!STOP.contains(term)) result.add(term);
                }
            } else result.add(word);
        }
        return result;
    }
}
