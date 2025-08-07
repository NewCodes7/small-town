package com.newcodes7.small_town.feedback;

import java.util.HashMap;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.amazonaws.Response;
import com.newcodes7.small_town.feedback.Feedback.FeedbackStatus;
import com.newcodes7.small_town.global.util.Client;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Controller
@Slf4j
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping("/api/feedback")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> submitFeedback(
            @RequestBody FeedbackCreateDto feedbackDto,
            HttpServletRequest request) {
        
        try {
            String ipAddress = Client.getClientIpAddress(request);
            String userAgent = request.getHeader("User-Agent");
            
            FeedbackResponseDto savedFeedback = feedbackService.createFeedback(feedbackDto, ipAddress, userAgent);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "피드백이 성공적으로 접수되었습니다.");
            response.put("feedbackId", savedFeedback.getId());
            
            return ResponseEntity.ok(response);
            
        } catch (RuntimeException e) {
            log.error("피드백 접수 중 오류 발생: {}", e.getMessage(), e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", e.getMessage());
            
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("피드백 접수 중 예상치 못한 오류 발생", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", "서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
            
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/admin/feedback")
    public String showFeedback(
        @RequestParam(required = false) String type,
        @RequestParam(required = false) FeedbackStatus status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        Model model
    ) {
        Page<Feedback> feedbacks = feedbackService.getFeedbacks(status, type, page, size);
        log.info("받은 피드백 {}", feedbacks.getContent());
        model.addAttribute("feedbacks", feedbacks);

        return "admin/feedback";
    }

    @PostMapping("admin/api/feedback/{feedbackId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateFeedbackStatus(
        @PathVariable Long feedbackId,
        @RequestBody FeedbackStatus status
    ) {
        Map<String, Object> response = new HashMap<>();

        feedbackService.updateFeedbackStatus(feedbackId, status);

        response.put("status", "success");
        response.put("message", "피드백의 상태가 성공적으로 변경되었습니다.");

        return ResponseEntity.ok(response);
    }
}
