package com.newcodes7.small_town.theme.repository;

import com.newcodes7.small_town.theme.entity.Theme;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ThemeRepository extends JpaRepository<Theme, Long> {

    // 활성화된 테마 조회 (삭제되지 않은 것만, 공개용)
    List<Theme> findByIsActiveTrueAndDeletedAtIsNullOrderByDisplayOrderAsc();

    // 전체 테마 조회 (삭제되지 않은 것만, 어드민용)
    List<Theme> findByDeletedAtIsNullOrderByDisplayOrderAsc();

    // ID로 조회 (삭제되지 않은 것만)
    Optional<Theme> findByIdAndDeletedAtIsNull(Long id);
}
