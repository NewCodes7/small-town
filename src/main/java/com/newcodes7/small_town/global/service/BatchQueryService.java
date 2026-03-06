package com.newcodes7.small_town.global.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BatchQueryService {

    private final EntityManager entityManager;

    /**
     * statement_timeout을 현재 트랜잭션 내에서만 비활성화하고 쿼리 실행.
     * SET LOCAL은 트랜잭션 종료 시 자동으로 connection-init-sql의 기본값(30s)으로 복원됨.
     * REFRESH MATERIALIZED VIEW, 전체 통계 갱신 등 정당하게 오래 걸리는 배치 쿼리에 사용.
     */
    @Transactional
    public void executeWithNoTimeout(Runnable query) {
        entityManager.createNativeQuery("SET LOCAL statement_timeout = 0").executeUpdate();
        query.run();
    }
}
