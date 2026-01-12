package com.newcodes7.small_town.theme.repository;

import com.newcodes7.small_town.theme.entity.Theme;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ThemeRepository extends JpaRepository<Theme, Long> {

    // 활성화된 테마 조회 (삭제되지 않은 것만, 공개용)
    List<Theme> findByIsActiveTrueAndDeletedAtIsNullOrderByDisplayOrderAsc();

    // 전체 테마 조회 (삭제되지 않은 것만, 어드민용)
    List<Theme> findByDeletedAtIsNullOrderByDisplayOrderAsc();

    // 전체 테마 조회 with Pagination (삭제되지 않은 것만, 어드민용)
    Page<Theme> findByDeletedAtIsNull(Pageable pageable);

    // ID로 조회 (삭제되지 않은 것만)
    Optional<Theme> findByIdAndDeletedAtIsNull(Long id);

    // 자동완성용 테마 검색 (이름 또는 자모 분리된 이름이 검색어로 시작하는 경우만)
    @Query("SELECT t FROM Theme t WHERE t.deletedAt IS NULL AND t.isActive = true " +
           "AND (LOWER(t.name) LIKE LOWER(CONCAT(:query, '%')) " +
           "OR LOWER(t.decomposedName) LIKE LOWER(CONCAT(:query, '%'))) " +
           "ORDER BY t.displayOrder ASC")
    List<Theme> findByNameOrDescriptionContainingIgnoreCaseAndIsActiveTrueAndDeletedAtIsNull(
            @Param("query") String query, Pageable pageable);

    // 자동완성용 테마 검색 (여러 패턴 지원, 처음부터 일치)
    @Query("SELECT t FROM Theme t WHERE t.deletedAt IS NULL AND t.isActive = true " +
           "AND (LOWER(t.name) LIKE LOWER(CONCAT(:pattern1, '%')) " +
           "OR LOWER(t.decomposedName) LIKE LOWER(CONCAT(:pattern1, '%')) " +
           "OR LOWER(t.name) LIKE LOWER(CONCAT(:pattern2, '%')) " +
           "OR LOWER(t.decomposedName) LIKE LOWER(CONCAT(:pattern2, '%'))) " +
           "ORDER BY t.displayOrder ASC")
    List<Theme> findByNameWithPatterns(
            @Param("pattern1") String pattern1,
            @Param("pattern2") String pattern2,
            Pageable pageable);

    // 조회수 증가 (원자적 연산으로 동시성 안전)
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Theme t SET t.viewCount = t.viewCount + 1 WHERE t.id = :themeId")
    int incrementViewCount(@Param("themeId") Long themeId);
}
