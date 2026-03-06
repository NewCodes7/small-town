package com.newcodes7.small_town.hackernews.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import com.newcodes7.small_town.hackernews.dto.HackerNewsCommentResponseDto;
import com.newcodes7.small_town.hackernews.dto.HackerNewsItemResponseDto;
import com.newcodes7.small_town.hackernews.entity.HackerNewsComment;
import com.newcodes7.small_town.hackernews.entity.HackerNewsItem;
import com.newcodes7.small_town.hackernews.service.HackerNewsService;

@ExtendWith(MockitoExtension.class)
class HackerNewsControllerTest {

    @Mock
    private HackerNewsService hackerNewsService;

    @InjectMocks
    private HackerNewsController hackerNewsController;

    // ===== 상세 페이지 테스트 =====

    @Test
    @DisplayName("detail: 상세 페이지 뷰와 모델 데이터 반환")
    void detail_뷰_반환() {
        // given
        HackerNewsItem item = createItem(1L, 100L, "Test Title", "테스트 제목", 200);
        HackerNewsItemResponseDto itemDto = new HackerNewsItemResponseDto(item);

        HackerNewsComment comment = createComment(1L, 201L, item, "user1", "Comment text", "댓글 번역", 0);
        HackerNewsCommentResponseDto commentDto = new HackerNewsCommentResponseDto(comment);

        when(hackerNewsService.getItem(1L)).thenReturn(itemDto);
        when(hackerNewsService.getComments(1L)).thenReturn(List.of(commentDto));

        Model model = new ConcurrentModel();

        // when
        String viewName = hackerNewsController.detail(1L, model);

        // then
        assertThat(viewName).isEqualTo("hackernews-detail");
        assertThat(model.getAttribute("item")).isEqualTo(itemDto);

        @SuppressWarnings("unchecked")
        List<HackerNewsCommentResponseDto> comments = (List<HackerNewsCommentResponseDto>) model.getAttribute("comments");
        assertThat(comments).hasSize(1);
    }

    // ===== API 테스트 =====

    @Test
    @DisplayName("getTopItems: 인기 아이템 목록 API - 기본 limit")
    void getTopItems_기본_limit() {
        // given
        HackerNewsItem item = createItem(1L, 100L, "Popular", "인기글", 500);
        HackerNewsItemResponseDto dto = new HackerNewsItemResponseDto(item);
        when(hackerNewsService.getTopItems(10)).thenReturn(List.of(dto));

        // when
        List<HackerNewsItemResponseDto> result = hackerNewsController.getTopItems(10);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Popular");
        verify(hackerNewsService).getTopItems(10);
    }

    @Test
    @DisplayName("getTopItems: limit 상한 100으로 제한")
    void getTopItems_limit_상한_제한() {
        // given
        when(hackerNewsService.getTopItems(100)).thenReturn(Collections.emptyList());

        // when
        hackerNewsController.getTopItems(200); // 200 요청 → Math.min(200, 100) = 100

        // then
        verify(hackerNewsService).getTopItems(100);
    }

    @Test
    @DisplayName("getLatestItems: 최신 아이템 목록 API")
    void getLatestItems_성공() {
        // given
        HackerNewsItem item = createItem(1L, 100L, "Latest", "최신글", 100);
        HackerNewsItemResponseDto dto = new HackerNewsItemResponseDto(item);
        when(hackerNewsService.getLatestItems(5)).thenReturn(List.of(dto));

        // when
        List<HackerNewsItemResponseDto> result = hackerNewsController.getLatestItems(5);

        // then
        assertThat(result).hasSize(1);
        verify(hackerNewsService).getLatestItems(5);
    }

    @Test
    @DisplayName("getLatestItems: limit 상한 100으로 제한")
    void getLatestItems_limit_상한_제한() {
        // given
        when(hackerNewsService.getLatestItems(100)).thenReturn(Collections.emptyList());

        // when
        hackerNewsController.getLatestItems(500);

        // then
        verify(hackerNewsService).getLatestItems(100);
    }

    @Test
    @DisplayName("getItem: 아이템 상세 API")
    void getItem_API() {
        // given
        HackerNewsItem item = createItem(1L, 100L, "Title", "제목", 300);
        HackerNewsItemResponseDto dto = new HackerNewsItemResponseDto(item);
        when(hackerNewsService.getItem(1L)).thenReturn(dto);

        // when
        HackerNewsItemResponseDto result = hackerNewsController.getItem(1L);

        // then
        assertThat(result.getTitle()).isEqualTo("Title");
        assertThat(result.getScore()).isEqualTo(300);
    }

    @Test
    @DisplayName("getComments: 댓글 목록 API")
    void getComments_API() {
        // given
        HackerNewsItem item = createItem(1L, 100L, "Title", null, 100);
        HackerNewsComment comment = createComment(1L, 201L, item, "user1", "Comment", "댓글", 0);
        HackerNewsCommentResponseDto dto = new HackerNewsCommentResponseDto(comment);
        when(hackerNewsService.getComments(1L)).thenReturn(List.of(dto));

        // when
        List<HackerNewsCommentResponseDto> result = hackerNewsController.getComments(1L);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTextContent()).isEqualTo("Comment");
        assertThat(result.get(0).getTranslatedText()).isEqualTo("댓글");
    }

    // ===== Admin API 테스트 =====

    @Test
    @DisplayName("crawlTopStories: 크롤링 트리거 결과 메시지 반환")
    void crawlTopStories_결과_메시지() {
        // given
        when(hackerNewsService.crawlTopStories()).thenReturn(5);

        // when
        String result = hackerNewsController.crawlTopStories();

        // then
        assertThat(result).isEqualTo("크롤링 완료: 5개 스토리 저장");
    }

    @Test
    @DisplayName("crawlComments: 댓글 크롤링 트리거 결과 메시지 반환")
    void crawlComments_결과_메시지() {
        // given
        when(hackerNewsService.crawlComments(1L)).thenReturn(10);

        // when
        String result = hackerNewsController.crawlComments(1L);

        // then
        assertThat(result).isEqualTo("댓글 크롤링 완료: 10개 댓글 저장");
    }

    // ===== Helper Methods =====

    private HackerNewsItem createItem(Long id, Long hnId, String title, String translatedTitle, int score) {
        HackerNewsItem item = HackerNewsItem.builder()
            .hnId(hnId)
            .title(title)
            .translatedTitle(translatedTitle)
            .url("https://example.com/article")
            .hnUrl("https://news.ycombinator.com/item?id=" + hnId)
            .author("testauthor")
            .score(score)
            .commentCount(10)
            .build();
        item.setId(id);
        return item;
    }

    private HackerNewsComment createComment(Long id, Long hnId, HackerNewsItem item, String author, String text, String translatedText, int depth) {
        HackerNewsComment comment = HackerNewsComment.builder()
            .hnId(hnId)
            .hackerNewsItem(item)
            .author(author)
            .textContent(text)
            .translatedText(translatedText)
            .depth(depth)
            .build();
        comment.setId(id);
        return comment;
    }
}
