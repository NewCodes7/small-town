package com.newcodes7.small_town.embedding.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.embedding.entity.ClovaChunkContent;

@Repository
public interface ClovaChunkContentRepository extends JpaRepository<ClovaChunkContent, Long> {
}
