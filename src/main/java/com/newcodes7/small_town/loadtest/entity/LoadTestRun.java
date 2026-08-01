package com.newcodes7.small_town.loadtest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 부하테스트 실행 기록 (Phase 1: 기록 관리 전용).
 * 실제 k6/ECS 실행은 여전히 터미널에서 사람이 하고, 이 엔티티는 생성된 실행 커맨드와
 * 사후 기록(Grafana 링크, 지표, pass/fail)을 보관한다.
 */
@Entity
@Table(
        name = "load_test_run",
        indexes = {@Index(name = "idx_load_test_run_created_at", columnList = "createdAt")})
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class LoadTestRun {

    public enum ExecutionType {
        LOCAL,
        FARGATE
    }

    public enum TargetEnv {
        LOCAL_DIRECT,
        LOCAL_NGINX,
        PRODUCTION
    }

    public enum Status {
        PLANNED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_run_id", length = 60)
    private String testRunId;

    @Column(name = "scenario", nullable = false, length = 50)
    private String scenario;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_type", nullable = false, length = 20)
    private ExecutionType executionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_env", nullable = false, length = 20)
    private TargetEnv targetEnv;

    @Column(name = "task_count")
    private Integer taskCount;

    @Column(name = "total_rate")
    private Integer totalRate;

    @Column(name = "total_vus")
    private Integer totalVus;

    @Column(name = "duration", length = 20)
    private String duration;

    @Column(name = "extra_env", columnDefinition = "TEXT")
    private String extraEnv;

    @Builder.Default
    @Column(name = "public_mode", nullable = false)
    private boolean publicMode = false;

    @Column(name = "generated_command", nullable = false, columnDefinition = "TEXT")
    private String generatedCommand;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PLANNED;

    @Column(name = "triggered_by", length = 100)
    private String triggeredBy;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "pass_fail")
    private Boolean passFail;

    @Column(name = "grafana_url", columnDefinition = "TEXT")
    private String grafanaUrl;

    @Column(name = "recorded_metrics", columnDefinition = "TEXT")
    private String recordedMetrics;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
