package com.newcodes7.small_town.embedding.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

import com.newcodes7.small_town.embedding.entity.ChunkContent;

@Repository
public interface ChunkContentRepository extends JpaRepository<ChunkContent, Long> {

    @Query("SELECT cc.content FROM ChunkContent cc WHERE cc.chunk.article.id = :articleId AND cc.chunk.isRepresentative = true")
    String findRepresentativeContentByArticleId(@Param("articleId") Long articleId);

    @Modifying
    @Query("DELETE FROM ChunkContent c WHERE c.chunk.article.id = :articleId")
    void deleteByChunkArticleId(@Param("articleId") Long articleId);

    @Modifying
    @Query("DELETE FROM ChunkContent c WHERE c.chunk.article.id IN :articleIds")
    void deleteByChunkArticleIdIn(@Param("articleIds") List<Long> articleIds);
}
