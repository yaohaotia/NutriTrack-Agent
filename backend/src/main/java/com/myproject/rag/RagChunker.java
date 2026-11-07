package com.myproject.rag;

import com.myproject.agent.AgentException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import static com.myproject.rag.RagModels.*;

public final class RagChunker {
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6} +(.+)$");
    private RagChunker() {}
    public static List<Chunk> split(String documentId, List<Page> pages, RagConfig config) {
        List<Chunk> result = new ArrayList<>();
        for (Page page : pages) {
            String text = page.text();
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(text.length(), start + config.getChunkSize());
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                if (end < text.length()) {
                    int boundary = text.lastIndexOf('\n', end - 1);
                    if (boundary > start + config.getChunkSize() / 2) end = boundary + 1;
                }
                String section = "";
                var headings = HEADING.matcher(text.substring(0, end));
                while (headings.find()) if (headings.start() <= start) section = headings.group(1).trim();
                section = section.substring(0, Math.min(section.length(), 160));
                String excerpt = text.substring(start, end);
                if (!excerpt.isBlank()) {
                    result.add(new Chunk(documentId + "-" + (result.size() + 1), page.number(), section, start, excerpt, null));
                    if (result.size() > config.getMaxDocumentChunks()) throw new AgentException(400, "文档分块数量超出上限，请拆分后导入");
                }
                if (end == text.length()) break;
                int next = Math.max(start + 1, end - config.getChunkOverlap());
                if (Character.isLowSurrogate(text.charAt(next))) next++;
                start = next;
            }
        }
        return List.copyOf(result);
    }
}
