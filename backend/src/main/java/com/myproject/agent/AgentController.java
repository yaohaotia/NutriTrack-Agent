package com.myproject.agent;

import com.myproject.result.ResponseResult;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import static com.myproject.agent.AgentModels.*;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentService service;
    private final AgentMemory memory;
    public AgentController(AgentService service, AgentMemory memory) { this.service = service; this.memory = memory; }

    @GetMapping("/status")
    public ResponseResult<Status> status(Principal principal) {
        username(principal);
        return ResponseResult.success(service.status());
    }

    @PostMapping("/chat")
    public ResponseResult<ChatResponse> chat(Principal principal, @RequestBody ChatRequest request) {
        return ResponseResult.success(service.chat(username(principal), request));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseResult<SessionView> session(Principal principal, @PathVariable("sessionId") String sessionId) {
        return ResponseResult.success(memory.view(username(principal), sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseResult<Void> delete(Principal principal, @PathVariable("sessionId") String sessionId) {
        memory.delete(username(principal), sessionId);
        return ResponseResult.success();
    }

    private static String username(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) throw new AgentException(401, "请先登录");
        return principal.getName();
    }
}
