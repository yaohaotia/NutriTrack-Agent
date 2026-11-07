package com.myproject.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myproject.agent.AgentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import static com.myproject.rag.RagModels.*;
import static org.junit.jupiter.api.Assertions.*;

class RagServiceTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    final RagDocumentParser parser = new RagDocumentParser();
    final RagEmbedding embedding = new RagEmbedding() {
        public String modelId() { return "unit-test-embedding"; }
        public int dimension() { return 4; }
        public float[] embed(String text) { return new float[]{1, text.contains("燕麦") ? 1 : 0, text.contains("控盐") ? 1 : 0, .1f}; }
    };
    RagConfig config() { var c = new RagConfig(); c.setDataDir(directory.toString()); return c; }
    RagService service() throws Exception { return new RagService(config(), embedding, parser, json); }
    byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test void persistsDeduplicatesVersionsAndDeletesWithoutLeavingSearchHits() throws Exception {
        RagService rag = service();
        var first = rag.upload(7L, null, "私人燕麦笔记", "https://example.com/source", "notes.md", bytes("# 私人笔记\n\n燕麦专属词abcdef"));
        var duplicate = rag.upload(7L, null, "不同标题", "", "same.md", bytes("# 私人笔记\n\n燕麦专属词abcdef"));
        assertTrue(duplicate.duplicate()); assertEquals(first.document().id(), duplicate.document().id());
        var reboot = service();
        assertEquals(1, reboot.status(7L).personalDocuments());
        assertEquals(1, reboot.search(7L, "abcdef", Mode.bm25, 10).hits().stream().filter(h -> h.documentId().equals(first.document().id())).count());
        var updated = reboot.upload(7L, first.document().id(), "新版", "", "updated.md", bytes("# 新版\n\n替换后专属词ghijkl"));
        assertEquals(2, updated.document().version());
        assertTrue(reboot.search(7L, "abcdef", Mode.bm25, 10).hits().stream().noneMatch(h -> h.documentId().equals(first.document().id())));
        assertTrue(service().detail(7L, first.document().id()).chunks().get(0).text().contains("ghijkl"));
        reboot.delete(7L, first.document().id());
        assertEquals(404, assertThrows(AgentException.class, () -> service().detail(7L, first.document().id())).status());
        assertTrue(service().search(7L, "ghijkl", Mode.hybrid, 10).hits().stream().noneMatch(h -> h.documentId().equals(first.document().id())));
    }

    @Test void filtersOwnerBeforeEveryRetrievalModeAndRejectsReadUpdateDeleteAcrossUsers() throws Exception {
        RagService rag = service();
        String id = rag.upload(7L, null, "私人资料", "", "secret.txt", bytes("燕麦私人机密secretxyz" )).document().id();
        assertEquals(404, assertThrows(AgentException.class, () -> rag.detail(8L, id)).status());
        assertEquals(404, assertThrows(AgentException.class, () -> rag.delete(8L, id)).status());
        assertEquals(404, assertThrows(AgentException.class, () -> rag.upload(8L, id, "篡改", "", "x.txt", bytes("篡改资料"))).status());
        for (Mode mode : Mode.values()) assertTrue(rag.search(8L, "secretxyz", mode, 10).hits().stream().noneMatch(h -> h.documentId().equals(id)));
        assertTrue(rag.list(8L).stream().noneMatch(d -> d.id().equals(id)));
        var ownCopy = rag.upload(8L, null, "我的资料", "", "secret.txt", bytes("燕麦私人机密secretxyz"));
        assertFalse(ownCopy.duplicate()); assertNotEquals(id, ownCopy.document().id());
        assertEquals(403, assertThrows(AgentException.class, () -> rag.delete(7L, "WHO-SODIUM")).status());
    }

    @Test void failedReplacementLeavesPreviousVersionAndQuotaFailureDoesNotPublish() throws Exception {
        RagConfig config = config(); config.setMaxUserDocuments(1);
        RagService rag = new RagService(config, embedding, parser, json);
        var doc = rag.upload(7L, null, "原资料", "", "a.txt", bytes("原有资料alpha"));
        assertThrows(AgentException.class, () -> rag.upload(7L, doc.document().id(), "损坏", "", "broken.pdf", bytes("not pdf")));
        assertEquals(1, rag.detail(7L, doc.document().id()).document().version());
        assertEquals(429, assertThrows(AgentException.class, () -> rag.upload(7L, null, "超额", "", "b.txt", bytes("不同内容beta"))).status());
        assertEquals(1, service().status(7L).personalDocuments());
    }

    @Test void validatesInputAndKeepsEmbeddingFingerprintAcrossRestart() throws Exception {
        RagService rag = service();
        assertThrows(AgentException.class, () -> rag.upload(7L, null, "", "javascript:alert(1)", "a.txt", bytes("内容")));
        assertThrows(AgentException.class, () -> rag.upload(7L, null, "", "", "evil.exe", bytes("内容")));
        assertThrows(AgentException.class, () -> rag.search(null, "控盐", Mode.hybrid, 3));
        assertThrows(AgentException.class, () -> rag.search(7L, "", Mode.hybrid, 3));
        RagEmbedding incompatible = new RagEmbedding() {
            public String modelId() { return "another-model"; }
            public int dimension() { return 4; }
            public float[] embed(String s) { return new float[]{1, 0, 0, 0}; }
        };
        assertThrows(java.io.IOException.class, () -> new RagService(config(), incompatible, parser, json));
    }

    @Test void chunkLocationsRoundTripAndDoNotMixPdfPages() {
        String page1 = "# 页一标题\n\n" + "燕麦与全谷物说明。".repeat(100);
        String page2 = "# 页二标题\n\n" + "控制盐的说明。".repeat(50);
        RagConfig config = config();
        var pages = List.of(new Page(2, page1), new Page(5, page2));
        var chunks = RagChunker.split("document", pages, config);
        assertTrue(chunks.size() > 2);
        for (Chunk chunk : chunks) {
            String page = chunk.page() == 2 ? page1 : page2;
            assertEquals(chunk.text(), page.substring(chunk.start(), chunk.start() + chunk.text().length()));
            assertTrue(chunk.text().length() <= config.getChunkSize());
        }
        assertTrue(chunks.stream().anyMatch(c -> c.page() == 5 && c.section().equals("页二标题")));
    }

    @Test void parsesPdfPhysicalPagesAndRejectsScansAndMalformedUtf8() throws Exception {
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(); pdf.addPage(page);
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                stream.beginText(); stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(30, 700); stream.showText("Oats and whole grains."); stream.endText();
            }
            var out = new java.io.ByteArrayOutputStream(); pdf.save(out);
            assertEquals(1, parser.parse("food.pdf", out.toByteArray()).get(0).number());
            assertTrue(parser.parse("food.pdf", out.toByteArray()).get(0).text().contains("Oats"));
        }
        try (var empty = new org.apache.pdfbox.pdmodel.PDDocument()) {
            empty.addPage(new org.apache.pdfbox.pdmodel.PDPage()); var out = new java.io.ByteArrayOutputStream(); empty.save(out);
            assertTrue(assertThrows(AgentException.class, () -> parser.parse("scan.pdf", out.toByteArray())).getMessage().contains("OCR"));
        }
        assertThrows(AgentException.class, () -> parser.parse("bad.txt", new byte[]{(byte)0xff}));
    }
}
