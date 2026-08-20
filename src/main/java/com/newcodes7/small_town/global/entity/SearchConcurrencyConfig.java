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
 * 검색 API 동시 실행 수 상한 설정 (V1_37).
 * 값 근거와 배경은 마이그레이션 주석과 SearchConcurrencyLimiter Javadoc 참고.
 */
@Entity
@Table(name = "search_concurrency_config")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchConcurrencyConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 한도를 적용할 경로. 현재는 'SEARCH' 하나. */
    @Column(name = "scope_name", nullable = false, unique = true, length = 30)
    private String scopeName;

    /** 동시에 처리할 최대 요청 수. */
    @Column(name = "max_concurrent", nullable = false)
    private Integer maxConcurrent;

    /** permit을 기다리는 최대 시간(ms). 이 시간을 넘기면 429. */
    @Column(name = "acquire_timeout_ms", nullable = false)
    private Integer acquireTimeoutMs;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
