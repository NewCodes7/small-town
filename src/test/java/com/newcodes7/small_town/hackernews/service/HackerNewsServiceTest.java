package com.newcodes7.small_town.hackernews.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.newcodes7.small_town.crawler.integration.deepl.DeeplService;
import com.newcodes7.small_town.hackernews.dto.HackerNewsCommentResponseDto;
import com.newcodes7.small_town.hackernews.dto.HackerNewsItemResponseDto;
import com.newcodes7.small_town.hackernews.entity.HackerNewsComment;
import com.newcodes7.small_town.hackernews.entity.HackerNewsItem;
import com.newcodes7.small_town.hackernews.integration.HackerNewsApiClient;
import com.newcodes7.small_town.hackernews.repository.HackerNewsCommentRepository;
import com.newcodes7.small_town.hackernews.repository.HackerNewsItemRepository;

@ExtendWith(MockitoExtension.class)
class HackerNewsServiceTest {

    @Mock
    private HackerNewsApiClient apiClient;

    @Mock
    private HackerNewsItemRepository itemRepository;

    @Mock
    private HackerNewsCommentRepository commentRepository;

    @Mock
    private DeeplService deeplService;

    private HackerNewsService hackerNewsService;

    @BeforeEach
    void setUp() {
        hackerNewsService = new HackerNewsService(apiClient, itemRepository, commentRepository, deeplService);
        ReflectionTestUtils.setField(hackerNewsService, "topStoriesLimit", 30);
        ReflectionTestUtils.setField(hackerNewsService, "maxCommentsPerItem", 20);
    }

    // ===== 크롤링 테스트 =====

    @Test
    @DisplayName("crawlTopStories: 신규 스토리 저장 성공")
    void crawlTopStories_신규_스토리_저장() {
        // given
        when(apiClient.getTopStoryIds()).thenReturn(List.of(100L, 200L));
        when(itemRepository.findExistingHnIds(anyList())).thenReturn(Collections.emptyList());

        Map<String, Object> storyData = createStoryData(100L, "Test Story", "https://example.com", "testuser", 150, 30, 1700000000L);
        when(apiClient.getItem(100L)).thenReturn(storyData);

        Map<String, Object> storyData2 = createStoryData(200L, "Another Story", "https://example2.com", "user2", 80, 10, 1700001000L);
        when(apiClient.getItem(200L)).thenReturn(storyData2);

        when(deeplService.containsKorean(anyString())).thenReturn(false);
        when(deeplService.translateTitle(anyString())).thenReturn("번역된 제목");
        when(itemRepository.save(any(HackerNewsItem.class))).thenAnswer(invocation -> {
            HackerNewsItem item = invocation.getArgument(0);
            item.setId(1L);
            return item;
        });

        // when
        int result = hackerNewsService.crawlTopStories();

        // then
        assertThat(result).isEqualTo(2);
        verify(itemRepository, times(2)).save(any(HackerNewsItem.class));
        verify(deeplService, times(2)).translateTitle(anyString());
    }

    @Test
    @DisplayName("crawlTopStories: 빈 top stories 시 0 반환")
    void crawlTopStories_빈_결과() {
        // given
        when(apiClient.getTopStoryIds()).thenReturn(Collections.emptyList());

        // when
        int result = hackerNewsService.crawlTopStories();

        // then
        assertThat(result).isEqualTo(0);
        verify(itemRepository, never()).save(any());
    }

    @Test
    @DisplayName("crawlTopStories: 이미 존재하는 아이템은 점수만 업데이트")
    void crawlTopStories_기존_아이템_업데이트() {
        // given
        when(apiClient.getTopStoryIds()).thenReturn(List.of(100L));
        when(itemRepository.findExistingHnIds(anyList())).thenReturn(List.of(100L));

        HackerNewsItem existingItem = HackerNewsItem.builder()
            .hnId(100L)
            .title("Old Title")
            .score(50)
            .commentCount(10)
            .build();
        existingItem.setId(1L);

        Map<String, Object> updatedData = createStoryData(100L, "Old Title", "https://example.com", "testuser", 200, 50, 1700000000L);
        when(apiClient.getItem(100L)).thenReturn(updatedData);
        when(itemRepository.findByHnId(100L)).thenReturn(Optional.of(existingItem));
        when(itemRepository.save(any(HackerNewsItem.class))).thenReturn(existingItem);

        // when
        int result = hackerNewsService.crawlTopStories();

        // then
        assertThat(result).isEqualTo(0); // 신규 저장 0건
        assertThat(existingItem.getScore()).isEqualTo(200);
        assertThat(existingItem.getCommentCount()).isEqualTo(50);
    }

    @Test
    @DisplayName("crawlTopStories: story 타입이 아닌 아이템은 건너뜀")
    void crawlTopStories_story가_아닌_타입_무시() {
        // given
        when(apiClient.getTopStoryIds()).thenReturn(List.of(100L));
        when(itemRepository.findExistingHnIds(anyList())).thenReturn(Collections.emptyList());

        Map<String, Object> jobData = new HashMap<>();
        jobData.put("id", 100L);
        jobData.put("type", "job");
        jobData.put("title", "Job Posting");
        when(apiClient.getItem(100L)).thenReturn(jobData);

        // when
        int result = hackerNewsService.crawlTopStories();

        // then
        assertThat(result).isEqualTo(0);
        verify(itemRepository, never()).save(any(HackerNewsItem.class));
    }

    @Test
    @DisplayName("crawlTopStories: 한국어 제목은 번역하지 않음")
    void crawlTopStories_한국어_제목_번역_스킵() {
        // given
        when(apiClient.getTopStoryIds()).thenReturn(List.of(100L));
        when(itemRepository.findExistingHnIds(anyList())).thenReturn(Collections.emptyList());

        Map<String, Object> storyData = createStoryData(100L, "한국어 제목", "https://example.com", "testuser", 100, 20, 1700000000L);
        when(apiClient.getItem(100L)).thenReturn(storyData);
        when(deeplService.containsKorean("한국어 제목")).thenReturn(true);
        when(itemRepository.save(any(HackerNewsItem.class))).thenAnswer(invocation -> {
            HackerNewsItem item = invocation.getArgument(0);
            item.setId(1L);
            return item;
        });

        // when
        int result = hackerNewsService.crawlTopStories();

        // then
        assertThat(result).isEqualTo(1);
        verify(deeplService, never()).translateTitle(anyString());

        ArgumentCaptor<HackerNewsItem> captor = ArgumentCaptor.forClass(HackerNewsItem.class);
        verify(itemRepository).save(captor.capture());
        assertThat(captor.getValue().getTranslatedTitle()).isNull();
    }

    // ===== 댓글 크롤링 테스트 =====

    @Test
    @DisplayName("crawlComments: 댓글 크롤링 및 번역 성공")
    void crawlComments_성공() {
        // given
        HackerNewsItem item = HackerNewsItem.builder()
            .hnId(100L)
            .title("Test Story")
            .build();
        item.setId(1L);

        when(itemRepository.findById(1L)).thenReturn(Optional.of(item));

        Map<String, Object> itemData = new HashMap<>();
        itemData.put("kids", List.of(201, 202));
        when(apiClient.getItem(100L)).thenReturn(itemData);

        when(commentRepository.findExistingHnIds(anyList())).thenReturn(Collections.emptyList());

        Map<String, Object> commentData = createCommentData(201L, 100L, "commentUser", "This is a <b>great</b> post!", 1700001000L);
        when(apiClient.getItem(201L)).thenReturn(commentData);

        Map<String, Object> commentData2 = createCommentData(202L, 100L, "commentUser2", "I agree!", 1700002000L);
        when(apiClient.getItem(202L)).thenReturn(commentData2);

        when(deeplService.containsKorean(anyString())).thenReturn(false);
        when(deeplService.translateTitle(anyString())).thenReturn("번역된 댓글");
        when(commentRepository.save(any(HackerNewsComment.class))).thenAnswer(invocation -> {
            HackerNewsComment comment = invocation.getArgument(0);
            comment.setId(1L);
            return comment;
        });

        // when
        int result = hackerNewsService.crawlComments(1L);

        // then
        assertThat(result).isEqualTo(2);
        verify(commentRepository, times(2)).save(any(HackerNewsComment.class));
    }

    @Test
    @DisplayName("crawlComments: 존재하지 않는 아이템은 예외 발생")
    void crawlComments_아이템_없음_예외() {
        // given
        when(itemRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> hackerNewsService.crawlComments(999L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HackerNewsItem not found");
    }

    @Test
    @DisplayName("crawlComments: 댓글 없는 아이템은 0 반환")
    void crawlComments_댓글_없음() {
        // given
        HackerNewsItem item = HackerNewsItem.builder()
            .hnId(100L)
            .title("Test Story")
            .build();
        item.setId(1L);

        when(itemRepository.findById(1L)).thenReturn(Optional.of(item));

        Map<String, Object> itemData = new HashMap<>();
        // kids 없음
        when(apiClient.getItem(100L)).thenReturn(itemData);

        // when
        int result = hackerNewsService.crawlComments(1L);

        // then
        assertThat(result).isEqualTo(0);
        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("crawlComments: deleted/dead 댓글은 건너뜀")
    void crawlComments_삭제된_댓글_스킵() {
        // given
        HackerNewsItem item = HackerNewsItem.builder()
            .hnId(100L)
            .title("Test Story")
            .build();
        item.setId(1L);

        when(itemRepository.findById(1L)).thenReturn(Optional.of(item));

        Map<String, Object> itemData = new HashMap<>();
        itemData.put("kids", List.of(201));
        when(apiClient.getItem(100L)).thenReturn(itemData);

        when(commentRepository.findExistingHnIds(anyList())).thenReturn(Collections.emptyList());

        Map<String, Object> deletedComment = new HashMap<>();
        deletedComment.put("id", 201L);
        deletedComment.put("type", "comment");
        deletedComment.put("deleted", true);
        when(apiClient.getItem(201L)).thenReturn(deletedComment);

        // when
        int result = hackerNewsService.crawlComments(1L);

        // then
        assertThat(result).isEqualTo(0);
        verify(commentRepository, never()).save(any());
    }

    // ===== 조회 테스트 =====

    @Test
    @DisplayName("getTopItems: 점수순으로 아이템 목록 반환")
    void getTopItems_점수순_조회() {
        // given
        HackerNewsItem item1 = createItemEntity(1L, 100L, "High Score", "번역1", 300);
        HackerNewsItem item2 = createItemEntity(2L, 200L, "Medium Score", "번역2", 150);

        when(itemRepository.findTopByScore(PageRequest.of(0, 10)))
            .thenReturn(List.of(item1, item2));

        // when
        List<HackerNewsItemResponseDto> result = hackerNewsService.getTopItems(10);

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTitle()).isEqualTo("High Score");
        assertThat(result.get(0).getTranslatedTitle()).isEqualTo("번역1");
        assertThat(result.get(0).getScore()).isEqualTo(300);
        assertThat(result.get(1).getScore()).isEqualTo(150);
    }

    @Test
    @DisplayName("getLatestItems: 최신순으로 아이템 목록 반환")
    void getLatestItems_최신순_조회() {
        // given
        HackerNewsItem item1 = createItemEntity(1L, 100L, "Latest", "최신글", 100);
        when(itemRepository.findLatest(PageRequest.of(0, 5)))
            .thenReturn(List.of(item1));

        // when
        List<HackerNewsItemResponseDto> result = hackerNewsService.getLatestItems(5);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Latest");
    }

    @Test
    @DisplayName("getItem: 아이템 상세 조회 성공")
    void getItem_성공() {
        // given
        HackerNewsItem item = createItemEntity(1L, 100L, "Test Title", "테스트 제목", 200);
        when(itemRepository.findById(1L)).thenReturn(Optional.of(item));

        // when
        HackerNewsItemResponseDto result = hackerNewsService.getItem(1L);

        // then
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getHnId()).isEqualTo(100L);
        assertThat(result.getTitle()).isEqualTo("Test Title");
        assertThat(result.getTranslatedTitle()).isEqualTo("테스트 제목");
        assertThat(result.getScore()).isEqualTo(200);
        assertThat(result.getDetailUrl()).isEqualTo("/hackernews/1");
        assertThat(result.getHnUrl()).isEqualTo("https://news.ycombinator.com/item?id=100");
    }

    @Test
    @DisplayName("getItem: 존재하지 않는 아이템 조회 시 예외")
    void getItem_없는_아이템_예외() {
        // given
        when(itemRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> hackerNewsService.getItem(999L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HackerNewsItem not found: 999");
    }

    @Test
    @DisplayName("getComments: 댓글 목록 조회 성공")
    void getComments_성공() {
        // given
        HackerNewsItem item = createItemEntity(1L, 100L, "Test", null, 100);

        HackerNewsComment comment1 = HackerNewsComment.builder()
            .hnId(201L)
            .hackerNewsItem(item)
            .author("user1")
            .textContent("English comment")
            .translatedText("한국어 댓글")
            .depth(0)
            .hnCreatedAt(LocalDateTime.of(2024, 1, 15, 10, 30))
            .build();
        comment1.setId(1L);

        HackerNewsComment comment2 = HackerNewsComment.builder()
            .hnId(202L)
            .hackerNewsItem(item)
            .author("user2")
            .textContent("Reply to comment")
            .translatedText("댓글에 대한 답글")
            .depth(1)
            .hnCreatedAt(LocalDateTime.of(2024, 1, 15, 11, 0))
            .build();
        comment2.setId(2L);

        when(commentRepository.findByHackerNewsItemId(1L))
            .thenReturn(List.of(comment1, comment2));

        // when
        List<HackerNewsCommentResponseDto> result = hackerNewsService.getComments(1L);

        // then
        assertThat(result).hasSize(2);

        assertThat(result.get(0).getAuthor()).isEqualTo("user1");
        assertThat(result.get(0).getTextContent()).isEqualTo("English comment");
        assertThat(result.get(0).getTranslatedText()).isEqualTo("한국어 댓글");
        assertThat(result.get(0).getDepth()).isEqualTo(0);

        assertThat(result.get(1).getAuthor()).isEqualTo("user2");
        assertThat(result.get(1).getDepth()).isEqualTo(1);
    }

    @Test
    @DisplayName("getComments: 댓글 없을 때 빈 리스트 반환")
    void getComments_빈_결과() {
        // given
        when(commentRepository.findByHackerNewsItemId(999L))
            .thenReturn(Collections.emptyList());

        // when
        List<HackerNewsCommentResponseDto> result = hackerNewsService.getComments(999L);

        // then
        assertThat(result).isEmpty();
    }

    // ===== Helper Methods =====

    private Map<String, Object> createStoryData(Long id, String title, String url, String author, int score, int descendants, long time) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("type", "story");
        data.put("title", title);
        data.put("url", url);
        data.put("by", author);
        data.put("score", score);
        data.put("descendants", descendants);
        data.put("time", time);
        return data;
    }

    private Map<String, Object> createCommentData(Long id, Long parent, String author, String text, long time) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("type", "comment");
        data.put("parent", parent);
        data.put("by", author);
        data.put("text", text);
        data.put("time", time);
        return data;
    }

    private HackerNewsItem createItemEntity(Long id, Long hnId, String title, String translatedTitle, int score) {
        HackerNewsItem item = HackerNewsItem.builder()
            .hnId(hnId)
            .title(title)
            .translatedTitle(translatedTitle)
            .url("https://example.com/article")
            .hnUrl("https://news.ycombinator.com/item?id=" + hnId)
            .author("testauthor")
            .score(score)
            .commentCount(10)
            .hnCreatedAt(LocalDateTime.of(2024, 1, 15, 10, 0))
            .build();
        item.setId(id);
        return item;
    }
}
