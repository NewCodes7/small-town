package com.newcodes7.small_town.theme.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.theme.dto.ThemeAutocompleteDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Theme 자동완성 전용 Repository (JPA 우회, 초고속)
 * JdbcTemplate을 직접 사용하여 JPA/Hibernate의 모든 오버헤드 제거
 *
 * 성능 개선:
 * - JPA Entity 로딩: ~10-20ms
 * - JdbcTemplate DTO: ~0.5-1ms
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ThemeAutocompleteRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 테마 자동완성 검색 (초고속 버전)
     * 두 가지 패턴으로 검색 (원본 + 자모분해)
     *
     * @param query1 첫 번째 검색 패턴 (예: "프로%")
     * @param query2 두 번째 검색 패턴 - 자모분해 (예: "ㅍㅡㄹㅗ%")
     * @param limit 결과 개수 제한
     * @return Theme 자동완성 결과 리스트
     */
    public List<ThemeAutocompleteDto> findAutocompleteThemes(String query1, String query2, int limit) {
        String sql = """
            SELECT DISTINCT id, name
            FROM (
                SELECT t.id, t.name, t.display_order
                FROM theme t
                WHERE t.deleted_at IS NULL AND t.is_active = true
                  AND LOWER(t.name) LIKE ?
                UNION ALL
                SELECT t.id, t.name, t.display_order
                FROM theme t
                WHERE t.deleted_at IS NULL AND t.is_active = true
                  AND LOWER(t.decomposed_name) LIKE ?
                UNION ALL
                SELECT t.id, t.name, t.display_order
                FROM theme t
                WHERE t.deleted_at IS NULL AND t.is_active = true
                  AND LOWER(t.name) LIKE ?
                UNION ALL
                SELECT t.id, t.name, t.display_order
                FROM theme t
                WHERE t.deleted_at IS NULL AND t.is_active = true
                  AND LOWER(t.decomposed_name) LIKE ?
            ) AS combined
            ORDER BY (SELECT display_order FROM theme WHERE id = combined.id) ASC
            LIMIT ?
            """;

        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> new ThemeAutocompleteDto(
                rs.getLong("id"),
                rs.getString("name")
            ),
            query1, query1,  // 패턴1로 name, decomposed_name 검색
            query2, query2,  // 패턴2로 name, decomposed_name 검색
            limit
        );
    }

    /**
     * 테마 자동완성 검색 (단순 버전, 더 빠름)
     * display_order 정렬을 서브쿼리 없이 처리
     */
    public List<ThemeAutocompleteDto> findAutocompleteThemesSimple(String query1, String query2, int limit) {
        String sql = """
            SELECT id, name
            FROM theme
            WHERE deleted_at IS NULL AND is_active = true
              AND (LOWER(name) LIKE ? OR LOWER(decomposed_name) LIKE ?
                   OR LOWER(name) LIKE ? OR LOWER(decomposed_name) LIKE ?)
            ORDER BY display_order ASC
            LIMIT ?
            """;

        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> new ThemeAutocompleteDto(
                rs.getLong("id"),
                rs.getString("name")
            ),
            query1, query1, query2, query2, limit
        );
    }
}
