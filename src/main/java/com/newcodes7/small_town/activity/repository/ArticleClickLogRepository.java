package com.newcodes7.small_town.activity.repository;

import com.newcodes7.small_town.activity.entity.ArticleClickLog;
import com.newcodes7.small_town.activity.entity.ReferralSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArticleClickLogRepository extends JpaRepository<ArticleClickLog, Long> {

    @Query(value = """
        SELECT acl FROM ArticleClickLog acl
        LEFT JOIN FETCH acl.user
        WHERE (:source IS NULL OR acl.source = :source)
        ORDER BY acl.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(acl) FROM ArticleClickLog acl
        WHERE (:source IS NULL OR acl.source = :source)
        """)
    Page<ArticleClickLog> findAllWithFilter(@Param("source") ReferralSource source, Pageable pageable);

    /** 유입경로(source)별 클릭 수 — 최근 7일 */
    @Query(value = """
        SELECT source, COUNT(*) AS cnt
        FROM article_click_log
        WHERE created_at >= NOW() - INTERVAL '7 days'
        GROUP BY source
        ORDER BY cnt DESC
        """, nativeQuery = true)
    List<Object[]> countBySourceLast7Days();

    @Query(value = "SELECT COUNT(*) FROM article_click_log", nativeQuery = true)
    long countAll();

    @Query(value = "SELECT COUNT(*) FROM article_click_log WHERE created_at >= CURRENT_DATE", nativeQuery = true)
    long countToday();
}
