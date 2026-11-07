package com.myproject.rag;

import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import org.springframework.stereotype.Component;

/** Real local ONNX embeddings; neither user documents nor queries leave the machine for indexing. */
@Component
public class LocalRagEmbedding implements RagEmbedding {
    private BgeSmallZhV15QuantizedEmbeddingModel model;
    public String modelId() { return "bge-small-zh-v1.5-quantized"; }
    public int dimension() { return 512; }
    public synchronized float[] embed(String text) {
        if (model == null) model = new BgeSmallZhV15QuantizedEmbeddingModel();
        return model.embed(text).content().vector();
    }
    public float[] embedQuery(String text) { return embed("为这个句子生成表示以用于检索相关文章：" + text); }
}
