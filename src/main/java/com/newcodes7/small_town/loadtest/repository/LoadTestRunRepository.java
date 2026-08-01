package com.newcodes7.small_town.loadtest.repository;

import com.newcodes7.small_town.loadtest.entity.LoadTestRun;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LoadTestRunRepository extends JpaRepository<LoadTestRun, Long> {

    /** 어드민 목록 조회용 필터 페이징 — RagQueryLogRepository.findAllWithFilter와 동일 패턴. */
    @Query(
            value =
                    """
        SELECT * FROM load_test_run r
        WHERE (:scenario IS NULL OR r.scenario = :scenario)
          AND (:status IS NULL OR r.status = :status)
          AND (CAST(:from AS timestamp) IS NULL OR r.created_at >= :from)
          AND (CAST(:to AS timestamp) IS NULL OR r.created_at <= :to)
        ORDER BY r.created_at DESC
        """,
            countQuery =
                    """
        SELECT COUNT(*) FROM load_test_run r
        WHERE (:scenario IS NULL OR r.scenario = :scenario)
          AND (:status IS NULL OR r.status = :status)
          AND (CAST(:from AS timestamp) IS NULL OR r.created_at >= :from)
          AND (CAST(:to AS timestamp) IS NULL OR r.created_at <= :to)
        """,
            nativeQuery = true)
    Page<LoadTestRun> findAllWithFilter(
            @Param("scenario") String scenario,
            @Param("status") String status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    long countByCreatedAtAfter(LocalDateTime since);

    long countByStatus(LoadTestRun.Status status);
}
