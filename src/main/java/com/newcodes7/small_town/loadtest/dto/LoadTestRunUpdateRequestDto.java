package com.newcodes7.small_town.loadtest.dto;

import com.newcodes7.small_town.loadtest.entity.LoadTestRun.Status;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 실행 후 기록 갱신용 — 필드는 전부 선택 입력, null이면 기존 값 유지. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoadTestRunUpdateRequestDto {
    private String testRunId;
    private Status status;
    private String triggeredBy;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Boolean passFail;
    private String grafanaUrl;
    private String recordedMetrics;
    private String notes;
}
