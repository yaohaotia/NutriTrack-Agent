package com.myproject.rag;

public interface RagEmbedding {
    String modelId();
    int dimension();
    float[] embed(String text);
    default float[] embedQuery(String text) { return embed(text); }
}
