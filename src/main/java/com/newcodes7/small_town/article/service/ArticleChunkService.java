package com.newcodes7.small_town.article.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.repository.ArticleChunkRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.ArticleChunk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Article 본문을 청크로 나누고 관리하는 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleChunkService {

    private final ArticleChunkRepository chunkRepository;

    // 청크 크기 설정
    private static final int TARGET_CHUNK_SIZE = 1000;  // 목표 크기
    private static final int MAX_CHUNK_SIZE = 1500;     // 최대 크기

    // 문장 종결 패턴 (한국어 + 영어)
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?。！？]\\s");

    /**
     * Article의 제목과 본문을 청크로 분할하여 저장
     *
     * @param article Article 엔티티
     * @return 생성된 청크 리스트
     */
    @Transactional
    public List<ArticleChunk> createChunksForArticle(Article article) {
        // 기존 청크 삭제
        chunkRepository.deleteByArticleId(article.getId());

        // 제목 + 본문 결합
        String fullText = buildFullText(article);

        if (fullText == null || fullText.trim().isEmpty()) {
            log.warn("Article {}의 본문이 비어있어 청크를 생성하지 않습니다", article.getId());
            return List.of();
        }

        // 텍스트를 청크로 분할
        List<String> chunks = splitIntoChunks(fullText);

        // ArticleChunk 엔티티 생성 및 저장
        List<ArticleChunk> articleChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ArticleChunk chunk = ArticleChunk.builder()
                    .article(article)
                    .chunkIndex(i)
                    .content(chunks.get(i))
                    .build();
            articleChunks.add(chunk);
        }

        List<ArticleChunk> savedChunks = chunkRepository.saveAll(articleChunks);
        log.info("Article {} - {}개 청크 생성 완료", article.getId(), savedChunks.size());

        return savedChunks;
    }

    /**
     * 제목과 본문을 결합
     * 제목을 3번 반복하여 가중치 부여
     */
    private String buildFullText(Article article) {
        StringBuilder sb = new StringBuilder();

        // 제목 3번 반복 (가중치 부여)
        String title = article.getTranslatedTitle() != null
                ? article.getTranslatedTitle()
                : article.getTitle();

        sb.append(title).append(". ");
        sb.append(title).append(". ");
        sb.append(title).append(". ");

        // 본문 추가
        if (article.getContent() != null && !article.getContent().trim().isEmpty()) {
            sb.append("\n\n");
            sb.append(article.getContent());
        }

        return sb.toString();
    }

    /**
     * 텍스트를 청크로 분할
     * 1000자 근처에서 문장 경계로 자름
     *
     * @param text 원본 텍스트
     * @return 청크 리스트
     */
    private List<String> splitIntoChunks(String text) {
        List<String> chunks = new ArrayList<>();
        int startIndex = 0;

        while (startIndex < text.length()) {
            int endIndex = findChunkEndIndex(text, startIndex);
            String chunk = text.substring(startIndex, endIndex).trim();

            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            startIndex = endIndex;
        }

        return chunks;
    }

    /**
     * 청크의 종료 위치 찾기
     * 1000자 근처에서 문장 끝을 찾음
     *
     * @param text 전체 텍스트
     * @param startIndex 시작 위치
     * @return 종료 위치
     */
    private int findChunkEndIndex(String text, int startIndex) {
        int remainingLength = text.length() - startIndex;

        // 남은 텍스트가 목표 크기보다 작으면 전부 포함
        if (remainingLength <= TARGET_CHUNK_SIZE) {
            return text.length();
        }

        // 목표 크기 근처에서 문장 끝 찾기
        int searchStart = startIndex + TARGET_CHUNK_SIZE;
        int searchEnd = Math.min(startIndex + MAX_CHUNK_SIZE, text.length());

        // searchStart부터 searchEnd까지 문장 종결 패턴 찾기
        Matcher matcher = SENTENCE_END.matcher(text.substring(searchStart, searchEnd));

        if (matcher.find()) {
            // 문장 끝을 찾았으면 그 위치에서 자름
            return searchStart + matcher.end();
        }

        // 문장 끝을 못 찾았으면 최대 크기에서 자름
        // 또는 공백 위치 찾기
        int maxEnd = Math.min(startIndex + MAX_CHUNK_SIZE, text.length());
        int lastSpace = text.lastIndexOf(' ', maxEnd);

        if (lastSpace > searchStart) {
            return lastSpace + 1;
        }

        return maxEnd;
    }

    /**
     * 특정 Article의 모든 청크 조회
     */
    public List<ArticleChunk> getChunksByArticleId(Long articleId) {
        return chunkRepository.findByArticleIdOrderByChunkIndexAsc(articleId);
    }

    /**
     * 특정 청크에 임베딩 저장
     */
    @Transactional
    public void saveEmbeddingToChunk(Long chunkId, float[] embedding) {
        ArticleChunk chunk = chunkRepository.findById(chunkId)
                .orElseThrow(() -> new IllegalArgumentException("Chunk not found: " + chunkId));

        chunk.setEmbedding(embedding);
        chunk.setEmbeddingGeneratedAt(LocalDateTime.now());
        chunkRepository.save(chunk);
    }

    /**
     * 임베딩이 없는 청크 조회
     */
    public List<ArticleChunk> getChunksWithoutEmbedding(int limit) {
        return chunkRepository.findChunksWithoutEmbedding(
                org.springframework.data.domain.PageRequest.of(0, limit)
        );
    }
}
