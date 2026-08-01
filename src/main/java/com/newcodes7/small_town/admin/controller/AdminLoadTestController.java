package com.newcodes7.small_town.admin.controller;

import com.newcodes7.small_town.admin.service.AdminLoadTestService;
import com.newcodes7.small_town.loadtest.dto.LoadTestRunCreateRequestDto;
import com.newcodes7.small_town.loadtest.dto.LoadTestRunUpdateRequestDto;
import com.newcodes7.small_town.loadtest.entity.LoadTestRun;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

/**
 * 부하테스트 실행 기록 어드민 페이지 (Phase 1: 기록 관리 전용 — 실제 k6/ECS 실행은 하지 않음).
 * 뷰(/admin/load-test)와 API(/api/admin/load-test/runs)의 프리픽스가 달라
 * AdminRagHistoryController와 동일하게 클래스 레벨 매핑 없이 절대 경로를 쓴다.
 */
@Controller
@RequiredArgsConstructor
public class AdminLoadTestController {

    /** load-test/scenarios/*.js 파일명과 동기화 — 새 시나리오 스크립트 추가 시 여기만 갱신하면 됨. */
    private static final List<String> SCENARIOS = List.of(
            "baseline",
            "search-hybrid",
            "autocomplete",
            "rag-answer",
            "ramp-limit-finder",
            "spike",
            "soak",
            "rate-limit-check");

    private final AdminLoadTestService adminLoadTestService;

    @GetMapping("/admin/load-test")
    public String list(
            @RequestParam(required = false) String scenario,
            @RequestParam(required = false) LoadTestRun.Status status,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            Model uiModel) {

        LocalDateTime from = parseDateStart(dateFrom);
        LocalDateTime to = parseDateEnd(dateTo);

        Page<LoadTestRun> runs = adminLoadTestService.getRuns(scenario, status, from, to, page, size);

        uiModel.addAttribute("runs", runs);
        uiModel.addAttribute("scenario", scenario);
        uiModel.addAttribute("status", status);
        uiModel.addAttribute("dateFrom", dateFrom);
        uiModel.addAttribute("dateTo", dateTo);
        uiModel.addAttribute("scenarios", SCENARIOS);
        uiModel.addAttribute("statuses", LoadTestRun.Status.values());
        uiModel.addAttribute("executionTypes", LoadTestRun.ExecutionType.values());
        uiModel.addAttribute("targetEnvs", LoadTestRun.TargetEnv.values());
        uiModel.addAttribute("totalCount", adminLoadTestService.getTotalCount());
        uiModel.addAttribute("thisMonthCount", adminLoadTestService.getThisMonthCount());
        uiModel.addAttribute("failedCount", adminLoadTestService.getFailedCount());
        return "admin/load-test/index";
    }

    @PostMapping("/api/admin/load-test/runs")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> create(@RequestBody LoadTestRunCreateRequestDto req) {
        LoadTestRun run = adminLoadTestService.create(req);
        return ResponseEntity.ok(toDetailResponse(run));
    }

    @GetMapping("/api/admin/load-test/runs/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id) {
        return ResponseEntity.ok(toDetailResponse(adminLoadTestService.getDetail(id)));
    }

    @PatchMapping("/api/admin/load-test/runs/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id, @RequestBody LoadTestRunUpdateRequestDto req) {
        LoadTestRun run = adminLoadTestService.update(id, req);
        return ResponseEntity.ok(toDetailResponse(run));
    }

    @DeleteMapping("/api/admin/load-test/runs/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        adminLoadTestService.delete(id);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        return ResponseEntity.ok(response);
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

    private Map<String, Object> toDetailResponse(LoadTestRun run) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", run.getId());
        response.put("testRunId", run.getTestRunId());
        response.put("scenario", run.getScenario());
        response.put("executionType", run.getExecutionType());
        response.put("targetEnv", run.getTargetEnv());
        response.put("taskCount", run.getTaskCount());
        response.put("totalRate", run.getTotalRate());
        response.put("totalVus", run.getTotalVus());
        response.put("duration", run.getDuration());
        response.put("extraEnv", run.getExtraEnv());
        response.put("publicMode", run.isPublicMode());
        response.put("generatedCommand", run.getGeneratedCommand());
        response.put("status", run.getStatus());
        response.put("triggeredBy", run.getTriggeredBy());
        response.put("startedAt", run.getStartedAt());
        response.put("finishedAt", run.getFinishedAt());
        response.put("passFail", run.getPassFail());
        response.put("grafanaUrl", run.getGrafanaUrl());
        response.put("recordedMetrics", run.getRecordedMetrics());
        response.put("notes", run.getNotes());
        response.put("createdAt", run.getCreatedAt());
        response.put("updatedAt", run.getUpdatedAt());
        return response;
    }
}
