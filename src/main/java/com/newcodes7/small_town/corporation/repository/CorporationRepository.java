package com.newcodes7.small_town.corporation.repository;

import com.newcodes7.small_town.corporation.entity.Corporation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CorporationRepository extends JpaRepository<Corporation, Long> {
    
    // 소프트 삭제되지 않은 기업 조회
    @Query("SELECT c FROM Corporation c WHERE c.deletedAt IS NULL")
    List<Corporation> findAllActive();
    
    @Query("SELECT c FROM Corporation c WHERE c.deletedAt IS NULL")
    Page<Corporation> findAllActive(Pageable pageable);
    
    @Query("SELECT c FROM Corporation c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<Corporation> findActiveById(@Param("id") Long id);
    
    // 이름으로 검색 (소프트 삭제되지 않은 것만)
    @Query("SELECT c FROM Corporation c WHERE c.name LIKE %:name% AND c.deletedAt IS NULL")
    Page<Corporation> findByNameContainingAndDeletedAtIsNull(@Param("name") String name, Pageable pageable);
    
    boolean existsByNameAndDeletedAtIsNull(String name);
}