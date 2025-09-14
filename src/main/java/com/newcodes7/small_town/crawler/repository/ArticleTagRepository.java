package com.newcodes7.small_town.crawler.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.newcodes7.small_town.global.entity.ArticleTag;

public interface ArticleTagRepository extends JpaRepository<ArticleTag, Long> {

    @Modifying
    @Query("DELETE FROM ArticleTag at WHERE at.article.id = :articleId")
    void deleteByArticleId(@Param("articleId") Long articleId);
}
