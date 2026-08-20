package com.newcodes7.small_town.embedding.repository;

import com.newcodes7.small_town.global.entity.EmbeddingCircuitConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmbeddingCircuitConfigRepository extends JpaRepository<EmbeddingCircuitConfig, Long> {

    Optional<EmbeddingCircuitConfig> findByScopeName(String scopeName);
}
