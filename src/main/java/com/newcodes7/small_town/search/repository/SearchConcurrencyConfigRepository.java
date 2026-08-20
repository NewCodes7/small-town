package com.newcodes7.small_town.search.repository;

import com.newcodes7.small_town.global.entity.SearchConcurrencyConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SearchConcurrencyConfigRepository extends JpaRepository<SearchConcurrencyConfig, Long> {

    Optional<SearchConcurrencyConfig> findByScopeName(String scopeName);
}
