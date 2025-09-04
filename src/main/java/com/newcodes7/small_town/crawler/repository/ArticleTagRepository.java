package com.newcodes7.small_town.crawler.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.newcodes7.small_town.global.entity.ArticleTag;

public interface ArticleTagRepository extends JpaRepository<ArticleTag, Long> {
    
}
