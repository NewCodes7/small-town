package com.newcodes7.small_town.hackernews.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.crawler.integration.deepl.DeeplService;
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
    private final DeeplService deeplService;

    private static final int TOP_STORIES_LIMIT = 30;
    private static final int MAX_COMMENTS_PER_ITEM = 20;

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
            .limit(TOP_STORIES_LIMIT)
            .toList();

        // 이미 존재하는 ID 조회 (배치)
        Set<Long> existingHnIds = itemRepository.findExistingHnIds(targetIds)
            .stream().collect(Collectors.toSet());

        int savedCount = 0;
        for (Long storyId : targetIds) {
            try {
                if (existingHnIds.contains(storyId)) {
                    // 기존 아이템 점수 업데이트
                    updateExistingItem(storyId);
                    continue;
                }

                Map<String, Object> itemData = apiClient.getItem(storyId);
                if (itemData == null || !"story".equals(itemData.get("type"))) {
                    continue;
                }

                HackerNewsItem item = parseAndSaveItem(itemData);
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
     * 인기 아이템 목록 조회 (점수순)
     */
    @Transactional(readOnly = true)
    public List<HackerNewsItemResponseDto> getTopItems(int limit) {
        return itemRepository.findTopByScore(PageRequest.of(0, limit))
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

    private HackerNewsItem parseAndSaveItem(Map<String, Object> data) {
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
            if (title != null && !deeplService.containsKorean(title)) {
                try {
                    translatedTitle = deeplService.translateTitle(title);
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
                .build();

            return itemRepository.save(item);
        } catch (Exception e) {
            log.error("HackerNewsItem 파싱/저장 실패: {}", e.getMessage(), e);
            return null;
        }
    }

    private void updateExistingItem(Long hnId) {
        try {
            Map<String, Object> itemData = apiClient.getItem(hnId);
            if (itemData == null) return;

            HackerNewsItem item = itemRepository.findByHnId(hnId).orElse(null);
            if (item == null) return;

            Number scoreNum = (Number) itemData.get("score");
            Number descendantsNum = (Number) itemData.get("descendants");

            if (scoreNum != null) item.setScore(scoreNum.intValue());
            if (descendantsNum != null) item.setCommentCount(descendantsNum.intValue());

            itemRepository.save(item);
        } catch (Exception e) {
            log.warn("HN item {} 업데이트 실패: {}", hnId, e.getMessage());
        }
    }

    private int crawlCommentsRecursive(HackerNewsItem item, List<Number> kidIds, int depth, int savedCount) {
        if (kidIds == null || kidIds.isEmpty() || savedCount >= MAX_COMMENTS_PER_ITEM) {
            return savedCount;
        }

        // 이미 존재하는 댓글 ID 조회
        List<Long> longKidIds = kidIds.stream().map(Number::longValue).toList();
        Set<Long> existingIds = commentRepository.findExistingHnIds(longKidIds)
            .stream().collect(Collectors.toSet());

        for (Number kidId : kidIds) {
            if (savedCount >= MAX_COMMENTS_PER_ITEM) break;

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

                // HTML 태그 제거하여 순수 텍스트 추출
                String cleanText = text.replaceAll("<[^>]*>", " ")
                    .replaceAll("&amp;", "&")
                    .replaceAll("&lt;", "<")
                    .replaceAll("&gt;", ">")
                    .replaceAll("&quot;", "\"")
                    .replaceAll("&#x27;", "'")
                    .replaceAll("&#x2F;", "/")
                    .replaceAll("\\s+", " ")
                    .trim();

                // 댓글 번역
                String translatedText = null;
                if (!deeplService.containsKorean(cleanText)) {
                    try {
                        translatedText = deeplService.translateTitle(cleanText);
                    } catch (Exception e) {
                        log.warn("댓글 번역 실패: {}", e.getMessage());
                    }
                }

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
                    .translatedText(translatedText)
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
