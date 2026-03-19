package com.newcodes7.small_town.article.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.article.dto.TermAutocompleteDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Term 자동완성 전용 Repository (JPA 우회, 초고속)
 * JdbcTemplate을 직접 사용하여 JPA/Hibernate의 모든 오버헤드 제거
 * 성능: 92ms → 1-2ms
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TermAutocompleteRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Term 자동완성 검색 (초고속 버전 + Covering Index 최적화)
     * JPA를 완전히 우회하여 순수 JDBC만 사용
     * UNION ALL 방식으로 각 인덱스를 확실하게 사용
     *
     * 성능: 92ms → 1-2ms (JPA 우회) → 0.3ms (UNION ALL + Covering Index)
     *
     * @param query 검색 패턴 (예: "ㅋㅂ%") - 이미 소문자 변환 및 % 포함되어 전달됨
     * @param limit 결과 개수 제한
     * @return Term 자동완성 결과 리스트
     */
    public List<TermAutocompleteDto> findAutocompleteTerms(String query, int limit) {
        String sql = """
            SELECT term, MAX(total_frequency) AS total_frequency
            FROM (
                SELECT term, total_frequency FROM term
                WHERE term LIKE ? AND total_frequency > 0
                UNION ALL
                SELECT term, total_frequency FROM term
                WHERE decomposed_term LIKE ? AND total_frequency > 0
                UNION ALL
                SELECT term, total_frequency FROM term
                WHERE chosung LIKE ? AND total_frequency > 0
            ) AS combined
            GROUP BY term
            ORDER BY total_frequency DESC
            LIMIT ?
            """;

        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> new TermAutocompleteDto(
                rs.getString("term"),
                rs.getLong("total_frequency")
            ),
            query, query, query,
            limit
        );
    }
}
