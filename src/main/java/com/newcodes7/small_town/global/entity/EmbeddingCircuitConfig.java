package com.newcodes7.small_town.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Clova 임베딩 호출 서킷 브레이커 설정 (V1_38).
 * 값 근거는 마이그레이션 주석과 EmbeddingCircuitBreaker Javadoc 참고.
 */
@Entity
@Table(name = "embedding_circuit_config")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmbeddingCircuitConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_name", nullable = false, unique = true, length = 40)
    private String scopeName;

    /** false면 차단기를 통과시키지 않고 그대로 호출한다 (킬 스위치). */
    @Column(nullable = false)
    private Boolean enabled;

    /** 실패율 임계치(%). 슬라이딩 윈도우 내 실패 비율이 이 값을 넘으면 OPEN. */
    @Column(name = "failure_rate_threshold", nullable = false)
    private Double failureRateThreshold;

    /** 느린 호출 비율 임계치(%). Clova 장애는 에러보다 느려짐으로 오는 경우가 많다. */
    @Column(name = "slow_call_rate_threshold", nullable = false)
    private Double slowCallRateThreshold;

    /** 이 시간을 넘긴 호출을 '느린 호출'로 집계한다. */
    @Column(name = "slow_call_duration_ms", nullable = false)
    private Integer slowCallDurationMs;

    /** OPEN 상태를 유지하는 시간. 이후 HALF_OPEN으로 넘어가 탐침한다. */
    @Column(name = "wait_duration_open_ms", nullable = false)
    private Integer waitDurationOpenMs;

    @Column(name = "sliding_window_size", nullable = false)
    private Integer slidingWindowSize;

    /** 이 횟수 미만이면 비율을 계산하지 않는다 (한두 번의 실패로 열리지 않게). */
    @Column(name = "minimum_number_of_calls", nullable = false)
    private Integer minimumNumberOfCalls;

    @Column(name = "permitted_calls_half_open", nullable = false)
    private Integer permittedCallsHalfOpen;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
