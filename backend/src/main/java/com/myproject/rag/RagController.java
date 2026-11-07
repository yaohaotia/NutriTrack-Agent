package com.myproject.rag;

import com.myproject.agent.AgentException;
import com.myproject.mapper.UserMapper;
import com.myproject.result.ResponseResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.security.Principal;
import static com.myproject.rag.RagModels.*;

@RestController
@RequestMapping("/api/knowledge")
public class RagController {
    private final RagService rag;
    private final UserMapper users;
    public RagController(RagService rag, UserMapper users) { this.rag = rag; this.users = users; }
    @GetMapping("/status") public ResponseResult<Status> status(Principal p) { return ResponseResult.success(rag.status(user(p))); }
    @GetMapping("/documents") public ResponseResult<java.util.List<DocumentView>> list(Principal p) { return ResponseResult.success(rag.list(user(p))); }
    @GetMapping("/documents/{id}") public ResponseResult<DocumentDetail> detail(Principal p, @PathVariable("id") String id) { return ResponseResult.success(rag.detail(user(p), id)); }
    @PostMapping("/documents") public ResponseResult<ImportResult> upload(Principal p, @RequestParam("file") MultipartFile file,
            @RequestParam(name="title", required=false) String title, @RequestParam(name="sourceUrl", required=false) String sourceUrl) {
        return ResponseResult.success(importFile(user(p), null, file, title, sourceUrl));
    }
    @PutMapping("/documents/{id}") public ResponseResult<ImportResult> replace(Principal p, @PathVariable("id") String id,
            @RequestParam("file") MultipartFile file, @RequestParam(name="title", required=false) String title,
            @RequestParam(name="sourceUrl", required=false) String sourceUrl) {
        return ResponseResult.success(importFile(user(p), id, file, title, sourceUrl));
    }
    @DeleteMapping("/documents/{id}") public ResponseResult<Void> delete(Principal p, @PathVariable("id") String id) { rag.delete(user(p), id); return ResponseResult.success(null); }
    @PostMapping("/search") public ResponseResult<SearchResult> search(Principal p, @RequestBody SearchRequest request) {
        return ResponseResult.success(rag.search(user(p), request.query(), request.mode(), request.topK() == null ? 3 : request.topK()));
    }
    private ImportResult importFile(Long userId, String id, MultipartFile file, String title, String sourceUrl) {
        if (file.getSize() > 10 * 1024 * 1024 || file.isEmpty()) throw new AgentException(400, "文件须为1字节至10MB");
        try { return rag.upload(userId, id, title, sourceUrl, file.getOriginalFilename(), file.getBytes()); }
        catch (IOException ex) { throw new AgentException(400, "无法读取上传文件，请重试"); }
    }
    private Long user(Principal p) {
        if (p == null || p.getName() == null) throw new AgentException(401, "请先登录");
        var user = users.selectByUsername(p.getName());
        if (user == null) throw new AgentException(401, "登录用户不存在");
        return user.getId();
    }
}
