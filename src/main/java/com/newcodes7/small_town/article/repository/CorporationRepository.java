package com.newcodes7.small_town.article.repository;

import com.newcodes7.small_town.article.entity.Corporation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository("articleCorporationRepository")
public interface CorporationRepository extends JpaRepository<Corporation, Long> {
    
    @Query("SELECT c FROM ArticleCorporation c WHERE c.id = :id AND c.deletedAt IS NULL")
    Optional<Corporation> findActiveById(@Param("id") Long id);
}