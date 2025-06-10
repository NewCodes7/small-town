package com.newcodes7.small_town.corporation.repository;

import com.newcodes7.small_town.corporation.entity.Industry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IndustryRepository extends JpaRepository<Industry, Integer> {
    Optional<Industry> findByName(String name);
    boolean existsByName(String name);
}