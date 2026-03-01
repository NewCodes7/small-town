package com.newcodes7.small_town.search.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.newcodes7.small_town.global.entity.SearchWeightConfig;

public interface SearchWeightConfigRepository extends JpaRepository<SearchWeightConfig, Long> {
    Optional<SearchWeightConfig> findByComplexity(String complexity);
}
