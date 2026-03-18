package com.newcodes7.small_town.article.service;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.search.repository.ArticleAnalyzedContentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * article_analyzed_content 테이블 정합성 관리 서비스
 * article_search_view Materialized View 대체 - Term CRUD 시 호출하여 동기화
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleAnalyzedContentService {

    private final ArticleAnalyzedContentRepository articleAnalyzedContentRepository;
    private final EntityManager entityManager;

    /**
     * 단일 article의 article_analyzed_content row를 재계산/갱신
     * Term 추출, 관리자 Term 수정 후 호출
     */
    @Transactional
    public void rebuildForArticle(Article article) {
        articleAnalyzedContentRepository.upsertForArticle(article.getId());
        log.debug("article_analyzed_content 갱신 완료: article_id={}", article.getId());
    }

    /**
     * 여러 article의 article_analyzed_content row를 bulk UPSERT로 재계산/갱신
     * Term 삭제(불용어 등록) 시 영향받는 모든 article 대상으로 호출
     * statement_timeout 비활성화 (영향 article 수가 많을 수 있음)
     */
    @Transactional
    public void rebuildForAffectedArticles(List<Long> articleIds) {
        if (articleIds.isEmpty()) {
            return;
        }

        log.info("article_analyzed_content bulk 갱신 시작: {}개 article", articleIds.size());
        long startTime = System.currentTimeMillis();

        // 대량 업데이트 가능하므로 statement_timeout 비활성화
        entityManager.createNativeQuery("SET LOCAL statement_timeout = 0").executeUpdate();

        entityManager.createNativeQuery("""
                INSERT INTO article_analyzed_content (id, title, published_at, corporation_id, category_id, title_terms, content_terms, updated_at)
                SELECT
                    a.id, a.title, a.published_at, a.corporation_id, a.category_id,
                    STRING_AGG(CASE WHEN at.source IN ('TITLE','BOTH') THEN t.term END, ' ' ORDER BY at.score DESC),
                    STRING_AGG(CASE WHEN at.source IN ('CONTENT','BOTH') THEN t.term END, ' ' ORDER BY at.score DESC),
                    NOW()
                FROM article a
                LEFT JOIN article_term at ON a.id = at.article_id
                LEFT JOIN term t ON at.term_id = t.id
                WHERE a.id IN :articleIds AND a.deleted_at IS NULL
                GROUP BY a.id, a.title, a.published_at, a.corporation_id, a.category_id
                ON CONFLICT (id) DO UPDATE SET
                    title_terms = EXCLUDED.title_terms,
                    content_terms = EXCLUDED.content_terms,
                    updated_at = NOW()
                """)
                .setParameter("articleIds", articleIds)
                .executeUpdate();

        log.info("article_analyzed_content bulk 갱신 완료: {}개 article, {}ms",
                articleIds.size(), System.currentTimeMillis() - startTime);
    }

    /**
     * 단일 article의 article_analyzed_content row 삭제
     * Article soft delete 시 호출
     */
    @Transactional
    public void deleteForArticle(Long articleId) {
        articleAnalyzedContentRepository.deleteByArticleId(articleId);
        log.debug("article_analyzed_content 삭제 완료: article_id={}", articleId);
    }
}
