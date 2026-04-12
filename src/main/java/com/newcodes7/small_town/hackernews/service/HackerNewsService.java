package com.newcodes7.small_town.hackernews.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.crawler.integration.translation.TranslationService;
import com.newcodes7.small_town.hackernews.dto.HackerNewsCommentResponseDto;
import com.newcodes7.small_town.hackernews.dto.HackerNewsItemResponseDto;
import com.newcodes7.small_town.hackernews.entity.HackerNewsComment;
import com.newcodes7.small_town.hackernews.entity.HackerNewsItem;
import com.newcodes7.small_town.hackernews.integration.HackerNewsApiClient;
import com.newcodes7.small_town.hackernews.repository.HackerNewsCommentRepository;
import com.newcodes7.small_town.hackernews.repository.HackerNewsItemRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class HackerNewsService {

    private final HackerNewsApiClient apiClient;
    private final HackerNewsItemRepository itemRepository;
    private final HackerNewsCommentRepository commentRepository;
    private final TranslationService translationService;

    @Value("${hackernews.crawl.top-stories-limit:30}")
    private int topStoriesLimit;

    @Value("${hackernews.crawl.max-comments-per-item:20}")
    private int maxCommentsPerItem;

    /**
     * Hacker News 인기 스토리 크롤링 및 DB 저장
     */
    @Transactional
    public int crawlTopStories() {
        log.info("Hacker News 인기 스토리 크롤링 시작");

        List<Long> topStoryIds = apiClient.getTopStoryIds();
        if (topStoryIds.isEmpty()) {
            log.warn("Hacker News top stories가 비어있음");
            return 0;
        }

        // 상위 N개만 처리
        List<Long> targetIds = topStoryIds.stream()
            .limit(topStoriesLimit)
            .toList();

        // 이미 존재하는 ID 조회 (배치)
        Set<Long> existingHnIds = itemRepository.findExistingHnIds(targetIds)
            .stream().collect(Collectors.toSet());

        LocalDateTime batchTime = LocalDateTime.now();
        int savedCount = 0;
        for (int i = 0; i < targetIds.size(); i++) {
            Long storyId = targetIds.get(i);
            int rank = i + 1;
            try {
                if (existingHnIds.contains(storyId)) {
                    // 기존 아이템 점수 및 순위 업데이트
                    updateExistingItem(storyId, rank, batchTime);
                    continue;
                }

                Map<String, Object> itemData = apiClient.getItem(storyId);
                if (itemData == null || !"story".equals(itemData.get("type"))) {
                    continue;
                }

                HackerNewsItem item = parseAndSaveItem(itemData, rank, batchTime);
                if (item != null) {
                    savedCount++;
                }

                // API 호출 간 딜레이
                Thread.sleep(100);
            } catch (Exception e) {
                log.error("Hacker News 스토리 {} 처리 중 오류: {}", storyId, e.getMessage());
            }
        }

        log.info("Hacker News 크롤링 완료: {}개 새 스토리 저장", savedCount);
        return savedCount;
    }

    /**
     * Hacker News 인기 스토리 크롤링 + 댓글까지 함께 크롤링
     * 스케줄러에서 사용: 스토리 저장 후 각 신규 아이템의 댓글도 크롤링
     */
    @Transactional
    public int crawlTopStoriesWithComments() {
        log.info("Hacker News 인기 스토리 + 댓글 크롤링 시작");

        List<Long> topStoryIds = apiClient.getTopStoryIds();
        if (topStoryIds.isEmpty()) {
            log.warn("Hacker News top stories가 비어있음");
            return 0;
        }

        List<Long> targetIds = topStoryIds.stream()
            .limit(topStoriesLimit)
            .toList();

        Set<Long> existingHnIds = itemRepository.findExistingHnIds(targetIds)
            .stream().collect(Collectors.toSet());

        LocalDateTime batchTime = LocalDateTime.now();
        int savedCount = 0;
        int totalComments = 0;

        for (int i = 0; i < targetIds.size(); i++) {
            Long storyId = targetIds.get(i);
            int rank = i + 1;
            try {
                if (existingHnIds.contains(storyId)) {
                    updateExistingItem(storyId, rank, batchTime);
                    continue;
                }

                Map<String, Object> itemData = apiClient.getItem(storyId);
                if (itemData == null || !"story".equals(itemData.get("type"))) {
                    continue;
                }

                HackerNewsItem item = parseAndSaveItem(itemData, rank, batchTime);
                if (item != null) {
                    savedCount++;

                    // 새로 저장된 아이템의 댓글도 크롤링
                    try {
                        int commentCount = crawlCommentsForItem(item, itemData);
                        totalComments += commentCount;
                    } catch (Exception e) {
                        log.warn("스토리 {} 댓글 크롤링 실패: {}", storyId, e.getMessage());
                    }
                }

                Thread.sleep(100);
            } catch (Exception e) {
                log.error("Hacker News 스토리 {} 처리 중 오류: {}", storyId, e.getMessage());
            }
        }

        log.info("Hacker News 크롤링 완료: {}개 새 스토리, {}개 댓글 저장", savedCount, totalComments);
        return savedCount;
    }

    /**
     * 특정 아이템의 댓글 크롤링 및 저장
     */
    @Transactional
    public int crawlComments(Long itemId) {
        HackerNewsItem item = itemRepository.findById(itemId)
            .orElseThrow(() -> new IllegalArgumentException("HackerNewsItem not found: " + itemId));

        Map<String, Object> itemData = apiClient.getItem(item.getHnId());
        if (itemData == null) {
            log.warn("Hacker News item {} API 조회 실패", item.getHnId());
            return 0;
        }

        @SuppressWarnings("unchecked")
        List<Number> kidIds = (List<Number>) itemData.get("kids");
        if (kidIds == null || kidIds.isEmpty()) {
            log.info("댓글 없음: HN item {}", item.getHnId());
            return 0;
        }

        int savedCount = 0;
        savedCount = crawlCommentsRecursive(item, kidIds, 0, savedCount);

        log.info("댓글 크롤링 완료: HN item {} - {}개 댓글 저장", item.getHnId(), savedCount);
        return savedCount;
    }

    /**
     * 인기 아이템 목록 조회 (최신 크롤링 배치 기준, rank 순)
     */
    @Transactional(readOnly = true)
    public List<HackerNewsItemResponseDto> getTopItems(int limit) {
        return itemRepository.findByLatestBatch(PageRequest.of(0, limit))
            .stream()
            .map(HackerNewsItemResponseDto::new)
            .toList();
    }

    /**
     * 최신 아이템 목록 조회
     */
    @Transactional(readOnly = true)
    public List<HackerNewsItemResponseDto> getLatestItems(int limit) {
        return itemRepository.findLatest(PageRequest.of(0, limit))
            .stream()
            .map(HackerNewsItemResponseDto::new)
            .toList();
    }

    /**
     * 아이템 상세 조회
     */
    @Transactional(readOnly = true)
    public HackerNewsItemResponseDto getItem(Long id) {
        HackerNewsItem item = itemRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("HackerNewsItem not found: " + id));
        return new HackerNewsItemResponseDto(item);
    }

    /**
     * 아이템의 댓글 목록 조회
     */
    @Transactional(readOnly = true)
    public List<HackerNewsCommentResponseDto> getComments(Long itemId) {
        return commentRepository.findByHackerNewsItemId(itemId)
            .stream()
            .map(HackerNewsCommentResponseDto::new)
            .toList();
    }

    // ===== Private Methods =====

    /**
     * 이미 조회된 itemData를 활용해 댓글 크롤링 (API 재호출 방지)
     */
    private int crawlCommentsForItem(HackerNewsItem item, Map<String, Object> itemData) {
        @SuppressWarnings("unchecked")
        List<Number> kidIds = (List<Number>) itemData.get("kids");
        if (kidIds == null || kidIds.isEmpty()) {
            return 0;
        }

        int savedCount = crawlCommentsRecursive(item, kidIds, 0, 0);
        log.info("댓글 크롤링 완료: HN item {} - {}개 댓글 저장", item.getHnId(), savedCount);
        return savedCount;
    }

    private HackerNewsItem parseAndSaveItem(Map<String, Object> data, int rank, LocalDateTime batchTime) {
        try {
            String title = (String) data.get("title");
            String url = (String) data.get("url");
            String author = (String) data.get("by");
            Number scoreNum = (Number) data.get("score");
            Number descendantsNum = (Number) data.get("descendants");
            Number timeNum = (Number) data.get("time");
            Number idNum = (Number) data.get("id");

            long hnId = idNum.longValue();
            int score = scoreNum != null ? scoreNum.intValue() : 0;
            int descendants = descendantsNum != null ? descendantsNum.intValue() : 0;

            LocalDateTime hnCreatedAt = timeNum != null ?
                LocalDateTime.ofInstant(Instant.ofEpochSecond(timeNum.longValue()), ZoneId.of("Asia/Seoul")) :
                null;

            // 제목 번역
            String translatedTitle = null;
            if (title != null && !translationService.containsKorean(title)) {
                try {
                    translatedTitle = translationService.translateTitle(title);
                } catch (Exception e) {
                    log.warn("제목 번역 실패: {} - {}", title, e.getMessage());
                }
            }

            HackerNewsItem item = HackerNewsItem.builder()
                .hnId(hnId)
                .title(title)
                .translatedTitle(translatedTitle)
                .url(url)
                .hnUrl("https://news.ycombinator.com/item?id=" + hnId)
                .author(author)
                .score(score)
                .commentCount(descendants)
                .hnCreatedAt(hnCreatedAt)
                .rank(rank)
                .crawlBatchAt(batchTime)
                .build();

            return itemRepository.save(item);
        } catch (Exception e) {
            log.error("HackerNewsItem 파싱/저장 실패: {}", e.getMessage(), e);
            return null;
        }
    }

    private void updateExistingItem(Long hnId, int rank, LocalDateTime batchTime) {
        try {
            Map<String, Object> itemData = apiClient.getItem(hnId);
            if (itemData == null) return;

            HackerNewsItem item = itemRepository.findByHnId(hnId).orElse(null);
            if (item == null) return;

            Number scoreNum = (Number) itemData.get("score");
            Number descendantsNum = (Number) itemData.get("descendants");

            if (scoreNum != null) item.setScore(scoreNum.intValue());
            if (descendantsNum != null) item.setCommentCount(descendantsNum.intValue());
            item.setRank(rank);
            item.setCrawlBatchAt(batchTime);

            itemRepository.save(item);
        } catch (Exception e) {
            log.warn("HN item {} 업데이트 실패: {}", hnId, e.getMessage());
        }
    }

    private int crawlCommentsRecursive(HackerNewsItem item, List<Number> kidIds, int depth, int savedCount) {
        if (kidIds == null || kidIds.isEmpty() || savedCount >= maxCommentsPerItem) {
            return savedCount;
        }

        // 이미 존재하는 댓글 ID 조회
        List<Long> longKidIds = kidIds.stream().map(Number::longValue).toList();
        Set<Long> existingIds = commentRepository.findExistingHnIds(longKidIds)
            .stream().collect(Collectors.toSet());

        for (Number kidId : kidIds) {
            if (savedCount >= maxCommentsPerItem) break;

            long commentHnId = kidId.longValue();
            if (existingIds.contains(commentHnId)) continue;

            try {
                Map<String, Object> commentData = apiClient.getItem(commentHnId);
                if (commentData == null || !"comment".equals(commentData.get("type"))) continue;

                // deleted 또는 dead 댓글 건너뜀
                Boolean deleted = (Boolean) commentData.get("deleted");
                Boolean dead = (Boolean) commentData.get("dead");
                if (Boolean.TRUE.equals(deleted) || Boolean.TRUE.equals(dead)) continue;

                String text = (String) commentData.get("text");
                if (text == null || text.trim().isEmpty()) continue;

                // Jsoup을 사용한 HTML 태그 제거 및 엔티티 디코딩
                String cleanText = Jsoup.parse(text).text().trim();

                Number timeNum = (Number) commentData.get("time");
                LocalDateTime hnCreatedAt = timeNum != null ?
                    LocalDateTime.ofInstant(Instant.ofEpochSecond(timeNum.longValue()), ZoneId.of("Asia/Seoul")) :
                    null;

                Number parentNum = (Number) commentData.get("parent");

                HackerNewsComment comment = HackerNewsComment.builder()
                    .hnId(commentHnId)
                    .hackerNewsItem(item)
                    .parentHnId(parentNum != null ? parentNum.longValue() : null)
                    .author((String) commentData.get("by"))
                    .textContent(cleanText)
                    .translatedText(null)
                    .hnCreatedAt(hnCreatedAt)
                    .depth(depth)
                    .build();

                commentRepository.save(comment);
                savedCount++;

                // 대댓글은 depth 1까지만 처리
                if (depth < 1) {
                    @SuppressWarnings("unchecked")
                    List<Number> subKids = (List<Number>) commentData.get("kids");
                    if (subKids != null && !subKids.isEmpty()) {
                        savedCount = crawlCommentsRecursive(item, subKids, depth + 1, savedCount);
                    }
                }

                Thread.sleep(100);
            } catch (Exception e) {
                log.error("댓글 {} 처리 중 오류: {}", commentHnId, e.getMessage());
            }
        }

        return savedCount;
    }
}
