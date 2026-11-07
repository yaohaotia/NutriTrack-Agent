package com.myproject.rag;

import com.myproject.agent.AgentException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static com.myproject.rag.RagModels.*;

@Component
public class RagDocumentParser {
    public List<Page> parse(String fileName, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024) throw new AgentException(400, "文件须为1字节至10MB");
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".pdf")) return pdf(bytes);
            if (!name.endsWith(".txt") && !name.endsWith(".md")) throw new AgentException(400, "支持PDF、UTF-8 TXT和Markdown文件");
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            text = clean(text);
            if (text.isBlank() || text.indexOf('\0') >= 0) throw new AgentException(400, "文档没有可用文本，请检查文件内容");
            if (text.length() > 70_000) throw new AgentException(400, "单个文档最多支持7万字符，请拆分后导入");
            return List.of(new Page(1, text));
        } catch (CharacterCodingException ex) {
            throw new AgentException(400, "文本须使用UTF-8编码");
        } catch (IOException ex) {
            throw new AgentException(400, "文档解析失败，请检查文件是否损坏或加密");
        }
    }

    private List<Page> pdf(byte[] bytes) throws IOException {
        if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new AgentException(400, "PDF文件格式无效");
        }
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            if (pdf.isEncrypted() || !pdf.getCurrentAccessPermission().canExtractContent()) throw new AgentException(400, "无法提取该PDF文本");
            if (pdf.getNumberOfPages() > 100) throw new AgentException(400, "PDF最多支持100页，请拆分后导入");
            List<Page> pages = new ArrayList<>();
            int characters = 0;
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                String text = clean(stripper.getText(pdf));
                characters += text.length();
                if (characters > 70_000) throw new AgentException(400, "单个文档最多支持7万字符，请拆分后导入");
                if (!text.isBlank()) pages.add(new Page(page, text));
            }
            if (pages.isEmpty()) throw new AgentException(400, "PDF没有可提取文本；扫描件请先进行OCR");
            return List.copyOf(pages);
        }
    }

    private static String clean(String text) {
        return text.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }
}
