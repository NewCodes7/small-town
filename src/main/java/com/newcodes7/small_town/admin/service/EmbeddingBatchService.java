package com.newcodes7.small_town.admin.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.embedding.service.ChunkEmbeddingBatchService;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Article 임베딩 배치 생성 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingBatchService {

    private final ChunkEmbeddingBatchService chunkEmbeddingBatchService;

    /**
     * 여러 Article의 청크 임베딩을 배치로 생성
     *
     * @param articles 임베딩 생성할 Article 리스트
     * @return 생성 결과 통계
     */
    @Transactional
    public Map<String, Object> generateChunkEmbeddingsBatch(List<Article> articles) {
        log.info("배치 청크 임베딩 생성 시작 - 총 {}개 Article", articles.size());
        return chunkEmbeddingBatchService.generateChunkEmbeddingsBatch(articles);
    }

    /**
     * 단일 Article의 청크 임베딩 생성
     *
     * @param article 임베딩 생성할 Article
     * @return 생성된 청크 수
     */
    @Transactional
    public int generateChunkEmbeddingsForSingleArticle(Article article) {
        try {
            log.info("단일 Article 청크 임베딩 생성 시작 - Article ID: {}", article.getId());
            return chunkEmbeddingBatchService.generateChunkEmbeddingsForArticle(article);

        } catch (Exception e) {
            log.error("단일 Article 청크 임베딩 생성 실패 - Article ID: {}", article.getId(), e);
            return 0;
        }
    }
}
