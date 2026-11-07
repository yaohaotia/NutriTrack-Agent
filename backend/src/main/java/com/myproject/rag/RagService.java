package com.myproject.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myproject.agent.AgentException;
import com.myproject.agent.KnowledgeSearch;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.myproject.rag.RagModels.*;

/** Single-process persistent document index. Only immutable snapshots are exposed to readers. */
@Service
public class RagService {
    private static final String PUBLIC = "public";
    private final RagConfig config;
    private final RagEmbedding embeddings;
    private final RagDocumentParser parser;
    private final ObjectMapper json;
    private final Path directory;
    private volatile Map<String, Document> documents = Map.of();
    private final java.util.concurrent.Semaphore imports = new java.util.concurrent.Semaphore(2);

    public RagService(RagConfig config, RagEmbedding embeddings, RagDocumentParser parser, ObjectMapper json) throws IOException {
        config.validate();
        this.config = config; this.embeddings = embeddings; this.parser = parser; this.json = json;
        directory = Path.of(config.getDataDir()).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Index directory must not be a symbolic link");
        Map<String, Document> loaded = new LinkedHashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path file : paths.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                if (Files.isSymbolicLink(file) || Files.size(file) > 15 * 1024 * 1024) throw new IOException("Invalid index file");
                Document doc = json.readValue(file.toFile(), Document.class);
                validateStored(doc);
                if (!file.getFileName().toString().equals(doc.id() + ".json")) throw new IOException("Index identifier mismatch");
                loaded.put(doc.id(), doc);
            }
        }
        documents = Map.copyOf(loaded);
        seed();
    }

    public Status status(Long userId) {
        List<Document> visible = visible(userId);
        return new Status(embeddings.modelId(), embeddings.dimension(), "local-persistent-exact-cosine", visible.size(),
                visible.stream().mapToInt(d -> d.chunks().size()).sum(),
                (int)visible.stream().filter(d -> !d.builtIn()).count(), config.getMinVectorScore());
    }

    public List<DocumentView> list(Long userId) {
        return visible(userId).stream().sorted(Comparator.comparing(Document::createdAt).reversed().thenComparing(Document::id))
                .map(RagService::view).toList();
    }

    public DocumentDetail detail(Long userId, String id) {
        Document doc = requireVisible(userId, id);
        return new DocumentDetail(view(doc), doc.chunks().stream()
                .map(c -> new ChunkView(c.id(), c.page(), c.section(), c.start(), c.text())).toList());
    }

    public ImportResult upload(Long userId, String replaceId, String title, String sourceUrl, String fileName, byte[] bytes) {
        if (!imports.tryAcquire()) throw new AgentException(429, "正在索引其他文档，请稍后重试");
        try { return uploadInternal(userId, replaceId, title, sourceUrl, fileName, bytes); }
        finally { imports.release(); }
    }

    private ImportResult uploadInternal(Long userId, String replaceId, String title, String sourceUrl, String fileName, byte[] bytes) {
        String owner = owner(userId);
        String name = safeFileName(fileName);
        String cleanTitle = title == null || title.isBlank() ? name : title.trim();
        if (cleanTitle.length() > 160) throw new AgentException(400, "标题最多160字符");
        String url = sourceUrl(sourceUrl);
        String hash = hash(bytes);
        Document previous = replaceId == null ? null : requireOwned(userId, replaceId);
        Document duplicate = documents.values().stream().filter(d -> d.owner().equals(owner) && d.contentHash().equals(hash)).findFirst().orElse(null);
        if (duplicate != null && (replaceId == null || (duplicate.id().equals(replaceId) && duplicate.title().equals(cleanTitle)
                && duplicate.sourceUrl().equals(url) && duplicate.fileName().equals(name)))) return new ImportResult(view(duplicate), true);
        if (duplicate != null && !duplicate.id().equals(replaceId)) throw new AgentException(409, "已有相同内容的文档，请直接使用该文档");
        List<Page> pages = parser.parse(name, bytes);
        String id = previous == null ? UUID.randomUUID().toString() : previous.id();
        Document indexed = index(id, owner, cleanTitle, name, url, hash, previous == null ? 1 : previous.version() + 1, false, pages);
        synchronized (this) {
            // Recheck after CPU work, before persisting or publishing a new snapshot.
            Document current = previous == null ? null : requireOwned(userId, replaceId);
            if (current != null && current.version() != previous.version()) throw new AgentException(409, "文档已更新，请刷新后重试");
            var duplicateNow = documents.values().stream().filter(d -> d.owner().equals(owner) && d.contentHash().equals(hash)).findFirst();
            if (duplicateNow.isPresent() && previous == null) return new ImportResult(view(duplicateNow.get()), true);
            if (duplicateNow.isPresent() && !duplicateNow.get().id().equals(id)) throw new AgentException(409, "已有相同内容的文档，请直接使用该文档");
            long ownDocs = documents.values().stream().filter(d -> d.owner().equals(owner) && !d.id().equals(id)).count();
            long ownChunks = documents.values().stream().filter(d -> d.owner().equals(owner) && !d.id().equals(id)).mapToInt(d -> d.chunks().size()).sum();
            if (ownDocs >= config.getMaxUserDocuments() || ownChunks + indexed.chunks().size() > config.getMaxUserChunks()) {
                throw new AgentException(429, "个人知识库容量已满，请删除旧文档后重试");
            }
            if (documents.values().stream().filter(d -> !d.id().equals(id)).mapToInt(d -> d.chunks().size()).sum() + indexed.chunks().size() > 10_000) {
                throw new AgentException(429, "知识库容量已满，请稍后重试");
            }
            persist(indexed);
            Map<String, Document> next = new LinkedHashMap<>(documents); next.put(id, indexed); documents = Map.copyOf(next);
        }
        return new ImportResult(view(indexed), false);
    }

    public synchronized void delete(Long userId, String id) {
        Document doc = requireOwned(userId, id);
        try { Files.delete(path(doc.id())); }
        catch (IOException ex) { throw new AgentException(503, "知识库暂时无法保存变更，请重试"); }
        Map<String, Document> next = new LinkedHashMap<>(documents); next.remove(id); documents = Map.copyOf(next);
    }

    public SearchResult search(Long userId, String query, Mode mode, int topK) {
        if (query == null || query.isBlank() || query.length() > 500 || !query.matches("(?s).*[\\p{L}\\p{N}].*") || topK < 1 || topK > 10) throw new AgentException(400, "问题须为1至500字符且包含文字，返回条数为1至10");
        long started = System.nanoTime();
        List<Document> visible = visible(userId); // Authorization precedes either retrieval branch.
        Mode selected = mode == null ? Mode.hybrid : mode;
        float[] vector = selected == Mode.bm25 ? null : checked(embeddings.embedQuery(query.trim()));
        List<Hit> hits = RagRetrieval.search(visible, query.trim(), vector, selected, topK, config.getMinVectorScore(), config.getMinLexicalCoverage());
        return new SearchResult(query.trim(), selected, hits, (System.nanoTime() - started) / 1_000_000);
    }

    private Document index(String id, String owner, String title, String fileName, String url, String hash,
                           int version, boolean builtIn, List<Page> pages) {
        List<Chunk> chunks = new ArrayList<>();
        for (Chunk chunk : RagChunker.split(id, pages, config)) {
            String context = title.substring(0, Math.min(24, title.length())) + "\n"
                    + chunk.section().substring(0, Math.min(24, chunk.section().length())) + "\n" + chunk.text();
            float[] vector = checked(embeddings.embed(context));
            chunks.add(new Chunk(chunk.id(), chunk.page(), chunk.section(), chunk.start(), chunk.text(), vector));
        }
        if (chunks.isEmpty()) throw new AgentException(400, "文档没有可索引文本");
        return new Document(1, id, owner, title, fileName, url, hash, version, Instant.now().toString(), embeddings.modelId(),
                embeddings.dimension(), builtIn, List.copyOf(pages), List.copyOf(chunks));
    }

    private void seed() throws IOException {
        try (var input = new ClassPathResource("agent-knowledge.json").getInputStream()) {
            KnowledgeSearch.Entry[] entries = json.readValue(input, KnowledgeSearch.Entry[].class);
            for (var entry : entries) {
                if (documents.containsKey(entry.id())) continue;
                String text = "# " + entry.title() + "\n\n" + entry.excerpt() + "\n\n关键词：" + String.join("、", entry.keywords());
                Document doc = index(entry.id(), PUBLIC, entry.title(), entry.id() + ".md", entry.url(), hash(text.getBytes(StandardCharsets.UTF_8)), 1, true, List.of(new Page(1, text)));
                publishSeed(doc);
            }
        }
        for (String file : List.of("nutritrack-records.md", "nutritrack-knowledge.md")) {
            String id = file.equals("nutritrack-records.md") ? "NUTRI-RECORDS" : "NUTRI-KNOWLEDGE";
            if (documents.containsKey(id)) continue;
            try (var input = new ClassPathResource("rag-documents/" + file).getInputStream()) {
                byte[] bytes = input.readAllBytes();
                String title = file.equals("nutritrack-records.md") ? "NutriTrack 饮食数据与统计口径" : "NutriTrack 知识库使用说明";
                publishSeed(index(id, PUBLIC, title, file, "", hash(bytes), 1, true, parser.parse(file, bytes)));
            }
        }
    }

    private void publishSeed(Document doc) {
        persist(doc); Map<String, Document> next = new LinkedHashMap<>(documents); next.put(doc.id(), doc); documents = Map.copyOf(next);
    }
    private List<Document> visible(Long userId) {
        String owner = owner(userId);
        return documents.values().stream().filter(d -> d.owner().equals(PUBLIC) || d.owner().equals(owner)).toList();
    }
    private Document requireVisible(Long userId, String id) {
        String owner = owner(userId); Document doc = documents.get(id);
        if (doc == null || (!doc.owner().equals(PUBLIC) && !doc.owner().equals(owner))) throw new AgentException(404, "文档不存在或已删除");
        return doc;
    }
    private Document requireOwned(Long userId, String id) {
        Document doc = requireVisible(userId, id);
        if (doc.builtIn()) throw new AgentException(403, "内置公共资料只读，可导入自己的文档");
        return doc;
    }
    private void persist(Document doc) {
        Path temp = null;
        try {
            temp = Files.createTempFile(directory, ".index-", ".tmp");
            json.writeValue(temp.toFile(), doc);
            try { Files.move(temp, path(doc.id()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, path(doc.id()), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ex) { throw new AgentException(503, "知识库暂时无法保存，请检查数据目录"); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { } }
    }
    private Path path(String id) {
        if (id == null || !id.matches("[A-Za-z0-9-]{1,80}")) throw new AgentException(400, "文档标识无效");
        Path path = directory.resolve(id + ".json").normalize();
        if (!path.startsWith(directory) || Files.isSymbolicLink(path)) throw new AgentException(400, "文档路径无效");
        return path;
    }
    private float[] checked(float[] vector) {
        if (vector == null || vector.length != embeddings.dimension()) throw new AgentException(503, "向量模型维度与索引不匹配");
        double norm = 0;
        for (float v : vector) { if (!Float.isFinite(v)) throw new AgentException(503, "向量模型返回无效数据"); norm += (double)v*v; }
        if (norm == 0) throw new AgentException(503, "向量模型返回空向量");
        return vector;
    }
    private void validateStored(Document doc) throws IOException {
        if (doc.schemaVersion() != 1 || !embeddings.modelId().equals(doc.embeddingModel()) || doc.dimension() != embeddings.dimension()
                || doc.owner() == null || doc.pages() == null || doc.chunks() == null || doc.chunks().isEmpty()) {
            throw new IOException("Stored RAG index is incompatible; reindex documents before changing embedding models");
        }
        path(doc.id());
        for (Chunk chunk : doc.chunks()) checked(chunk.vector());
    }
    private static String owner(Long userId) { if (userId == null || userId <= 0) throw new AgentException(401, "请先登录"); return userId.toString(); }
    private static String safeFileName(String name) {
        if (name == null || name.isBlank()) throw new AgentException(400, "请选择文件");
        name = name.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1);
        if (name.length() > 180) throw new AgentException(400, "文件名过长");
        return name;
    }
    private static String sourceUrl(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(value.trim());
            if (value.length() > 1000 || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
            return uri.toString();
        } catch (IllegalArgumentException ex) { throw new AgentException(400, "资料来源须为有效HTTPS链接"); }
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static DocumentView view(Document d) { return new DocumentView(d.id(), d.title(), d.fileName(), d.sourceUrl(), d.version(), d.createdAt(), d.pages().size(), d.chunks().size(), d.builtIn()); }
}
