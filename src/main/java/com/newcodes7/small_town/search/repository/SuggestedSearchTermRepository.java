package com.newcodes7.small_town.search.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.newcodes7.small_town.global.entity.SuggestedSearchTerm;

public interface SuggestedSearchTermRepository extends JpaRepository<SuggestedSearchTerm, Long> {
    List<SuggestedSearchTerm> findByActiveTrueOrderByDisplayOrderAsc();
}
