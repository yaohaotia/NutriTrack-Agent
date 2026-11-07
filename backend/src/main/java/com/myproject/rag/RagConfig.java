package com.myproject.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nutritrack.rag")
public class RagConfig {
    private String dataDir = "./.nutritrack-rag";
    private int chunkSize = 450;
    private int chunkOverlap = 60;
    private int maxDocumentChunks = 160;
    private int maxUserDocuments = 30;
    private int maxUserChunks = 1000;
    private double minVectorScore = 0.55;
    private double minLexicalCoverage = 0.15;

    void validate() {
        if (chunkSize < 100 || chunkSize > 500 || chunkOverlap < 0 || chunkOverlap >= chunkSize / 2
                || maxDocumentChunks < 1 || maxDocumentChunks > 300 || maxUserDocuments < 1
                || maxUserChunks < 1 || minVectorScore < 0 || minVectorScore > 1 || minLexicalCoverage < 0 || minLexicalCoverage > 1) {
            throw new IllegalArgumentException("Invalid RAG configuration");
        }
    }
}
