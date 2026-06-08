package com.newcodes7.small_town.term.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.term.repository.TermRepository;
import com.newcodes7.small_town.article.service.ArticleEmbeddingService;
import com.newcodes7.small_town.global.entity.Term;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Term 임베딩 생성 서비스
 * OpenAI API를 사용하여 Term의 임베딩 벡터를 생성하고 저장
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TermEmbeddingService {

    private final TermRepository termRepository;
    private final ArticleEmbeddingService embeddingService;

    /**
     * 임베딩이 없는 Term들에 대해 배치로 임베딩 생성
     *
     * @param batchSize 한 번에 처리할 Term 개수
     * @return 생성된 임베딩 개수
     */
    @Transactional
    public TermEmbeddingResult generateEmbeddingsForTerms(int batchSize) {
        long startTime = System.currentTimeMillis();

        // 임베딩이 없는 Term 조회
        Pageable pageable = PageRequest.of(0, batchSize);
        List<Term> termsWithoutEmbedding = termRepository.findTermsWithoutEmbedding(batchSize);

        if (termsWithoutEmbedding.isEmpty()) {
            log.info("임베딩 생성할 Term이 없습니다.");
            return new TermEmbeddingResult(0, 0, 0);
        }

        log.info("{}개의 Term에 대해 임베딩 생성 시작", termsWithoutEmbedding.size());

        int successCount = 0;
        int failedCount = 0;

        for (Term term : termsWithoutEmbedding) {
            try {
                // Term 텍스트로 임베딩 생성
                float[] embedding = embeddingService.generateEmbedding(term.getTerm());

                if (embedding != null) {
                    // Term에 임베딩 저장
                    term.setEmbedding(embedding);
                    term.setEmbeddingGeneratedAt(LocalDateTime.now());
                    termRepository.save(term);
                    successCount++;

                    log.debug("Term '{}' 임베딩 생성 완료 ({}차원)", term.getTerm(), embedding.length);
                } else {
                    log.warn("Term '{}' 임베딩 생성 실패 - null 반환", term.getTerm());
                    failedCount++;
                }

                // API rate limit 방지를 위해 약간의 딜레이
                if (successCount % 10 == 0) {
                    Thread.sleep(100);
                }

            } catch (Exception e) {
                log.error("Term '{}' 임베딩 생성 중 오류 발생", term.getTerm(), e);
                failedCount++;
            }
        }

        long processingTime = System.currentTimeMillis() - startTime;

        log.info("Term 임베딩 배치 완료 - 성공: {}, 실패: {}, 처리 시간: {}ms",
                successCount, failedCount, processingTime);

        return new TermEmbeddingResult(successCount, failedCount, processingTime);
    }

    /**
     * 특정 Term의 임베딩 생성
     *
     * @param termId Term ID
     * @return 성공 여부
     */
    @Transactional
    public boolean generateEmbeddingForTerm(Long termId) {
        Term term = termRepository.findById(termId)
                .orElseThrow(() -> new IllegalArgumentException("Term not found: " + termId));

        try {
            float[] embedding = embeddingService.generateEmbedding(term.getTerm());

            if (embedding != null) {
                term.setEmbedding(embedding);
                term.setEmbeddingGeneratedAt(LocalDateTime.now());
                termRepository.save(term);

                log.info("Term '{}' 임베딩 생성 완료", term.getTerm());
                return true;
            } else {
                log.warn("Term '{}' 임베딩 생성 실패 - null 반환", term.getTerm());
                return false;
            }
        } catch (Exception e) {
            log.error("Term '{}' 임베딩 생성 중 오류", term.getTerm(), e);
            return false;
        }
    }

    /**
     * Term 임베딩 통계 조회
     */
    public TermEmbeddingStats getEmbeddingStats() {
        long totalTerms = termRepository.countAllTerms();
        long termsWithEmbedding = termRepository.countTermsWithEmbedding();
        long termsWithoutEmbedding = totalTerms - termsWithEmbedding;

        return new TermEmbeddingStats(totalTerms, termsWithEmbedding, termsWithoutEmbedding);
    }

    /**
     * Term 임베딩 생성 결과 DTO
     */
    public static class TermEmbeddingResult {
        private final int successCount;
        private final int failedCount;
        private final long processingTimeMs;

        public TermEmbeddingResult(int successCount, int failedCount, long processingTimeMs) {
            this.successCount = successCount;
            this.failedCount = failedCount;
            this.processingTimeMs = processingTimeMs;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getFailedCount() {
            return failedCount;
        }

        public long getProcessingTimeMs() {
            return processingTimeMs;
        }
    }

    /**
     * Term 임베딩 통계 DTO
     */
    public static class TermEmbeddingStats {
        private final long totalTerms;
        private final long termsWithEmbedding;
        private final long termsWithoutEmbedding;

        public TermEmbeddingStats(long totalTerms, long termsWithEmbedding, long termsWithoutEmbedding) {
            this.totalTerms = totalTerms;
            this.termsWithEmbedding = termsWithEmbedding;
            this.termsWithoutEmbedding = termsWithoutEmbedding;
        }

        public long getTotalTerms() {
            return totalTerms;
        }

        public long getTermsWithEmbedding() {
            return termsWithEmbedding;
        }

        public long getTermsWithoutEmbedding() {
            return termsWithoutEmbedding;
        }

        public double getCompletionPercentage() {
            if (totalTerms == 0) return 0.0;
            return (double) termsWithEmbedding / totalTerms * 100.0;
        }
    }
}
