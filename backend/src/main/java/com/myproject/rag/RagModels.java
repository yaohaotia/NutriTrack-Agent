package com.myproject.rag;

import java.util.List;

public final class RagModels {
    private RagModels() {}
    public enum Mode { bm25, vector, hybrid }
    public record Page(int number, String text) {}
    public record Chunk(String id, int page, String section, int start, String text, float[] vector) {}
    public record Document(int schemaVersion, String id, String owner, String title, String fileName,
                           String sourceUrl, String contentHash, int version, String createdAt,
                           String embeddingModel, int dimension, boolean builtIn, List<Page> pages, List<Chunk> chunks) {}
    public record DocumentView(String id, String title, String fileName, String sourceUrl, int version,
                               String createdAt, int pages, int chunks, boolean builtIn) {}
    public record ChunkView(String id, int page, String section, int start, String text) {}
    public record DocumentDetail(DocumentView document, List<ChunkView> chunks) {}
    public record ImportResult(DocumentView document, boolean duplicate) {}
    public record SearchRequest(String query, Mode mode, Integer topK) {}
    public record Hit(String id, String documentId, String title, String sourceUrl, String chunkId, int page,
                      String section, String excerpt, double score, Double vectorScore, Double bm25Score, int documentVersion) {}
    public record SearchResult(String query, Mode mode, List<Hit> hits, long durationMs) {}
    public record Status(String embeddingModel, int dimension, String storage, int documents, int chunks,
                         int personalDocuments, double minVectorScore) {}
}
