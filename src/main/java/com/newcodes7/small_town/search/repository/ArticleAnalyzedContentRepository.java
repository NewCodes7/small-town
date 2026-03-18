package com.newcodes7.small_town.search.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.search.entity.ArticleAnalyzedContent;

@Repository
public interface ArticleAnalyzedContentRepository extends JpaRepository<ArticleAnalyzedContent, Long> {

    /**
     * 단일 article의 article_analyzed_content row를 UPSERT
     * article_term + term 테이블에서 STRING_AGG로 재계산
     */
    @Modifying
    @Query(value = """
        INSERT INTO article_analyzed_content (id, title, published_at, corporation_id, category_id, title_terms, content_terms, updated_at)
        SELECT
            a.id, a.title, a.published_at, a.corporation_id, a.category_id,
            STRING_AGG(CASE WHEN at.source IN ('TITLE','BOTH') THEN t.term END, ' ' ORDER BY at.score DESC),
            STRING_AGG(CASE WHEN at.source IN ('CONTENT','BOTH') THEN t.term END, ' ' ORDER BY at.score DESC),
            NOW()
        FROM article a
        LEFT JOIN article_term at ON a.id = at.article_id
        LEFT JOIN term t ON at.term_id = t.id
        WHERE a.id = :articleId AND a.deleted_at IS NULL
        GROUP BY a.id, a.title, a.published_at, a.corporation_id, a.category_id
        ON CONFLICT (id) DO UPDATE SET
            title_terms = EXCLUDED.title_terms,
            content_terms = EXCLUDED.content_terms,
            updated_at = NOW()
        """, nativeQuery = true)
    void upsertForArticle(@Param("articleId") Long articleId);

    /**
     * 특정 article의 row 삭제 (soft delete 시 호출)
     */
    @Modifying
    @Query(value = "DELETE FROM article_analyzed_content WHERE id = :articleId", nativeQuery = true)
    void deleteByArticleId(@Param("articleId") Long articleId);
}
