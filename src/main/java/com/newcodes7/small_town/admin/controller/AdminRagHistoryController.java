package com.newcodes7.small_town.admin.controller;

import com.newcodes7.small_town.admin.service.AdminRagHistoryService;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.search.config.RagModelProperties;
import com.newcodes7.small_town.search.entity.RagQueryLog;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

/**
 * RAG 질의응답 히스토리 어드민 조회 페이지.
 * 뷰(/admin/rag/history)와 상세 API(/api/admin/rag/history/{id})의 프리픽스가 달라
 * AdminRagController와 동일하게 클래스 레벨 매핑 없이 절대 경로를 쓴다.
 */
@Controller
@RequiredArgsConstructor
public class AdminRagHistoryController {

    private final AdminRagHistoryService adminRagHistoryService;
    private final CorporationRepository corporationRepository;
    private final RagModelProperties ragModelProperties;

    @GetMapping("/admin/rag/history")
    public String list(
            @RequestParam(required = false) RagQueryLog.Outcome outcome,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long corporationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            Model uiModel) {

        LocalDateTime from = parseDateStart(dateFrom);
        LocalDateTime to = parseDateEnd(dateTo);

        Page<RagQueryLog> logs = adminRagHistoryService.getLogs(
                outcome, model, from, to, keyword, corporationId, page, size);

        uiModel.addAttribute("logs", logs);
        uiModel.addAttribute("outcome", outcome);
        uiModel.addAttribute("model", model);
        uiModel.addAttribute("dateFrom", dateFrom);
        uiModel.addAttribute("dateTo", dateTo);
        uiModel.addAttribute("keyword", keyword);
        uiModel.addAttribute("corporationId", corporationId);
        uiModel.addAttribute("outcomes", RagQueryLog.Outcome.values());
        uiModel.addAttribute("corporations", corporationRepository.findAllByDeletedAtIsNullOrderByNameAsc());
        uiModel.addAttribute("ragModels", ragModelProperties.visibleModels());
        uiModel.addAttribute("totalCount", adminRagHistoryService.getTotalCount());
        uiModel.addAttribute("todayCount", adminRagHistoryService.getTodayCount());
        uiModel.addAttribute("todayErrorCount", adminRagHistoryService.getTodayErrorCount());
        return "admin/rag/history";
    }

    @GetMapping("/api/admin/rag/history/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id) {
        return ResponseEntity.ok(toDetailResponse(adminRagHistoryService.getDetail(id)));
    }

    private LocalDateTime parseDateStart(String date) {
        return parseDate(date, "dateFrom").map(LocalDate::atStartOfDay).orElse(null);
    }

    private LocalDateTime parseDateEnd(String date) {
        return parseDate(date, "dateTo").map(d -> d.atTime(LocalTime.MAX)).orElse(null);
    }

    private Optional<LocalDate> parseDate(String date, String paramName) {
        if (date == null || date.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(date));
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "잘못된 날짜 형식입니다: " + paramName);
        }
    }

    private Map<String, Object> toDetailResponse(RagQueryLog log) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", log.getId());
        response.put("createdAt", log.getCreatedAt());
        response.put("question", log.getQuestion());
        response.put("answer", log.getAnswer());
        response.put("outcome", log.getOutcome());
        response.put("model", log.getModel());
        response.put("extractedCorporations", log.getExtractedCorporations());
        response.put("extractedKeywords", log.getExtractedKeywords());
        response.put("vectorQuery", log.getVectorQuery());
        response.put("matchedCorporationIds", log.getMatchedCorporationIds());
        response.put("articleCount", log.getArticleCount());
        response.put("inputTokens", log.getInputTokens());
        response.put("outputTokens", log.getOutputTokens());
        response.put("totalTokens", log.getTotalTokens());
        response.put("conversationId", log.getConversationId());
        response.put("ipAddress", log.getIpAddress());
        response.put("userId", log.getUserId());
        return response;
    }
}
