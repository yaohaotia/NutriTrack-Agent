package com.myproject.controller;

import com.myproject.dto.ChatRequestDTO;
import com.myproject.result.ResponseResult;
import com.myproject.service.AIAnalysisService;
import com.myproject.vo.ChatMessageVO;
import com.myproject.vo.DailyAnalysisReportVO;
import com.myproject.vo.TrendResponseVO;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Legacy analysis endpoints use the same authenticated identity as the Agent. */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AIAnalysisController {
    private static final Logger log = LoggerFactory.getLogger(AIAnalysisController.class);
    private final AIAnalysisService aiAnalysisService;

    @PostMapping("/chat")
    public ResponseResult<ChatMessageVO> chat(@RequestBody ChatRequestDTO request, Principal principal) {
        String question = request.getQuestion();
        if (question == null || question.isBlank() || question.length() > 2000) {
            return ResponseResult.fail(400, "问题须为 1 到 2000 个字符");
        }
        try {
            return ResponseResult.success(aiAnalysisService.chatWithAI(null, principal.getName(), question));
        } catch (Exception e) {
            log.warn("Legacy AI chat failed", e);
            return ResponseResult.fail(502, "AI 服务暂时不可用，请稍后重试");
        }
    }

    @GetMapping("/trend")
    public TrendResponseVO getTrend(@RequestParam(name = "period", defaultValue = "7d") String period,
                                   Principal principal) throws Exception {
        int days = switch (period) {
            case "30d" -> 30;
            case "3m", "90d" -> 90;
            default -> 7;
        };
        return aiAnalysisService.getTrendAnalysis(null, principal.getName(), days);
    }

    @GetMapping("/daily-analysis")
    public ResponseResult<DailyAnalysisReportVO> getDailyAnalysis(
            @RequestParam(value = "date", required = false) String date, Principal principal) {
        try {
            LocalDate target = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
            return ResponseResult.success(aiAnalysisService.getOrGenerateDailyAnalysis(null, principal.getName(), target));
        } catch (DateTimeParseException e) {
            return ResponseResult.fail(400, "日期格式须为 yyyy-MM-dd");
        } catch (Exception e) {
            log.warn("Legacy daily analysis failed", e);
            return ResponseResult.fail(502, "分析服务暂时不可用，请检查模型配置后重试");
        }
    }
}
