package com.newcodes7.small_town.crawler.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.crawler.entity.ParsingSelector;

@Repository
public interface ParsingSelectorRepository extends JpaRepository<ParsingSelector, Long> {
    @Query("SELECT p FROM ParsingSelector p WHERE p.corporationId = :id")
    Optional<ParsingSelector> findOptionalByCorporationId(@Param("id") Long id);

    default ParsingSelector findByCorporationIdOrDefault(Long id) {
        return findOptionalByCorporationId(id)
            .orElseGet(() -> ParsingSelector.defaultSelector(id));
    }

    @Query("DELETE FROM ParsingSelector p WHERE p.corporationId = :corporationId")
    void deleteByCorporationId(@Param("corporationId") Long corporationId);
}
