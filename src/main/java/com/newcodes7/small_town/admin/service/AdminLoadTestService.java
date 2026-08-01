package com.newcodes7.small_town.admin.service;

import com.newcodes7.small_town.loadtest.dto.LoadTestRunCreateRequestDto;
import com.newcodes7.small_town.loadtest.dto.LoadTestRunUpdateRequestDto;
import com.newcodes7.small_town.loadtest.entity.LoadTestRun;
import com.newcodes7.small_town.loadtest.repository.LoadTestRunRepository;
import com.newcodes7.small_town.loadtest.service.LoadTestCommandBuilder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminLoadTestService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final LoadTestRunRepository loadTestRunRepository;
    private final LoadTestCommandBuilder loadTestCommandBuilder;

    public Page<LoadTestRun> getRuns(
            String scenario, LoadTestRun.Status status, LocalDateTime from, LocalDateTime to,
            int page, int size) {
        // 정렬은 findAllWithFilter의 네이티브 쿼리 자체 ORDER BY로 고정한다 — Pageable에 Sort를
        // 얹으면 Spring Data가 프로퍼티명(createdAt)을 컬럼명으로 변환하지 않고 그대로 덧붙여
        // "column r.createdat does not exist" 오류가 난다.
        return loadTestRunRepository.findAllWithFilter(
                (scenario != null && !scenario.isBlank()) ? scenario : null,
                status != null ? status.name() : null,
                from,
                to,
                PageRequest.of(page, size));
    }

    public LoadTestRun getDetail(Long id) {
        return loadTestRunRepository
                .findById(id)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "실행 기록을 찾을 수 없습니다: " + id));
    }

    public long getTotalCount() {
        return loadTestRunRepository.count();
    }

    public long getThisMonthCount() {
        LocalDateTime monthStart = LocalDate.now(KST).withDayOfMonth(1).atStartOfDay();
        return loadTestRunRepository.countByCreatedAtAfter(monthStart);
    }

    public long getFailedCount() {
        return loadTestRunRepository.countByStatus(LoadTestRun.Status.FAILED);
    }

    @Transactional
    public LoadTestRun create(LoadTestRunCreateRequestDto req) {
        if (req.getScenario() == null || req.getScenario().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시나리오를 선택해주세요.");
        }
        if (req.getExecutionType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "실행 방식을 선택해주세요.");
        }
        if (req.getTargetEnv() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "대상 환경을 선택해주세요.");
        }

        LoadTestCommandBuilder.Result result = loadTestCommandBuilder.build(
                req.getScenario(),
                req.getExecutionType(),
                req.getTargetEnv(),
                req.getTaskCount(),
                req.getTotalRate(),
                req.getTotalVus(),
                req.getDuration(),
                req.getExtraEnv(),
                req.isPublicMode());

        LoadTestRun run = LoadTestRun.builder()
                .testRunId(result.testRunId())
                .scenario(req.getScenario())
                .executionType(req.getExecutionType())
                .targetEnv(req.getTargetEnv())
                .taskCount(req.getTaskCount())
                .totalRate(req.getTotalRate())
                .totalVus(req.getTotalVus())
                .duration(req.getDuration())
                .extraEnv(req.getExtraEnv())
                .publicMode(req.isPublicMode())
                .generatedCommand(result.command())
                .status(LoadTestRun.Status.PLANNED)
                .triggeredBy((req.getTriggeredBy() != null && !req.getTriggeredBy().isBlank())
                        ? req.getTriggeredBy().trim()
                        : null)
                .build();

        return loadTestRunRepository.save(run);
    }

    @Transactional
    public LoadTestRun update(Long id, LoadTestRunUpdateRequestDto req) {
        LoadTestRun run = getDetail(id);

        if (req.getTestRunId() != null) {
            run.setTestRunId(req.getTestRunId().isBlank() ? null : req.getTestRunId().trim());
        }
        if (req.getStatus() != null) {
            run.setStatus(req.getStatus());
        }
        if (req.getTriggeredBy() != null) {
            run.setTriggeredBy(req.getTriggeredBy().isBlank() ? null : req.getTriggeredBy().trim());
        }
        if (req.getStartedAt() != null) {
            run.setStartedAt(req.getStartedAt());
        }
        if (req.getFinishedAt() != null) {
            run.setFinishedAt(req.getFinishedAt());
        }
        if (req.getPassFail() != null) {
            run.setPassFail(req.getPassFail());
        }
        if (req.getGrafanaUrl() != null) {
            run.setGrafanaUrl(req.getGrafanaUrl().isBlank() ? null : req.getGrafanaUrl().trim());
        }
        if (req.getRecordedMetrics() != null) {
            run.setRecordedMetrics(req.getRecordedMetrics());
        }
        if (req.getNotes() != null) {
            run.setNotes(req.getNotes());
        }

        try {
            return loadTestRunRepository.save(run);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "이미 사용 중인 testRunId입니다: " + req.getTestRunId());
        }
    }

    @Transactional
    public void delete(Long id) {
        if (!loadTestRunRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "실행 기록을 찾을 수 없습니다: " + id);
        }
        loadTestRunRepository.deleteById(id);
    }
}
