package com.newcodes7.small_town.corporation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.global.entity.Corporation;

@Repository
public interface CorporationRepository extends JpaRepository<Corporation, Long> {
    
    // 소프트 삭제되지 않은 기업 조회
    @EntityGraph(attributePaths = {"corporationIndustries", "corporationIndustries.industry"})
    @Query("SELECT c FROM Corporation c WHERE c.deletedAt IS NULL")
    List<Corporation> findAllActive();

    @EntityGraph(attributePaths = {"corporationIndustries", "corporationIndustries.industry"})
    @Query("SELECT c FROM Corporation c WHERE c.deletedAt IS NULL")
    Page<Corporation> findAllActive(Pageable pageable);

    @EntityGraph(attributePaths = {"corporationIndustries", "corporationIndustries.industry"})
    @Query("SELECT c FROM Corporation c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<Corporation> findActiveById(@Param("id") Long id);
    
    // 이름으로 검색 (소프트 삭제되지 않은 것만)
    @EntityGraph(attributePaths = {"corporationIndustries", "corporationIndustries.industry"})
    @Query("SELECT c FROM Corporation c WHERE c.name LIKE %:name% AND c.deletedAt IS NULL")
    Page<Corporation> findByNameContainingAndDeletedAtIsNull(@Param("name") String name, Pageable pageable);

    boolean existsByNameAndDeletedAtIsNull(String name);

    long countByDeletedAtIsNull();

    // 블로그 URL이 존재하는 기업 조회
    @EntityGraph(attributePaths = {"corporationIndustries", "corporationIndustries.industry"})
    @Query("SELECT c FROM Corporation c WHERE c.deletedAt IS NULL AND c.blogLink IS NOT NULL AND c.blogLink <> ''")
    Page<Corporation> findAllActiveWithBlog(Pageable pageable);

    // 유튜브 URL이 존재하는 기업 조회
    @EntityGraph(attributePaths = {"corporationIndustries", "corporationIndustries.industry"})
    @Query("SELECT c FROM Corporation c WHERE c.deletedAt IS NULL AND c.youtubeUrl IS NOT NULL AND c.youtubeUrl <> ''")
    Page<Corporation> findAllActiveWithYoutube(Pageable pageable);

    // 블로그 URL이 존재하는 기업 검색
    @EntityGraph(attributePaths = {"corporationIndustries", "corporationIndustries.industry"})
    @Query("SELECT c FROM Corporation c WHERE c.name LIKE %:name% AND c.deletedAt IS NULL AND c.blogLink IS NOT NULL AND c.blogLink <> ''")
    Page<Corporation> findByNameContainingWithBlog(@Param("name") String name, Pageable pageable);

    // 유튜브 URL이 존재하는 기업 검색
    @EntityGraph(attributePaths = {"corporationIndustries", "corporationIndustries.industry"})
    @Query("SELECT c FROM Corporation c WHERE c.name LIKE %:name% AND c.deletedAt IS NULL AND c.youtubeUrl IS NOT NULL AND c.youtubeUrl <> ''")
    Page<Corporation> findByNameContainingWithYoutube(@Param("name") String name, Pageable pageable);
}