package com.newcodes7.small_town.hackernews.dto;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.newcodes7.small_town.hackernews.entity.HackerNewsComment;
import com.newcodes7.small_town.hackernews.entity.HackerNewsItem;

class HackerNewsItemResponseDtoTest {

    @Test
    @DisplayName("http URL은 정상 반환")
    void sanitizeUrl_http_허용() {
        // given
        HackerNewsItem item = createItem("https://example.com/article");

        // when
        HackerNewsItemResponseDto dto = new HackerNewsItemResponseDto(item);

        // then
        assertThat(dto.getUrl()).isEqualTo("https://example.com/article");
    }

    @Test
    @DisplayName("http:// 프로토콜도 허용")
    void sanitizeUrl_http_프로토콜_허용() {
        // given
        HackerNewsItem item = createItem("http://example.com/article");

        // when
        HackerNewsItemResponseDto dto = new HackerNewsItemResponseDto(item);

        // then
        assertThat(dto.getUrl()).isEqualTo("http://example.com/article");
    }

    @Test
    @DisplayName("javascript: 프로토콜은 null로 차단")
    void sanitizeUrl_javascript_차단() {
        // given
        HackerNewsItem item = createItem("javascript:alert('xss')");

        // when
        HackerNewsItemResponseDto dto = new HackerNewsItemResponseDto(item);

        // then
        assertThat(dto.getUrl()).isNull();
    }

    @Test
    @DisplayName("data: 프로토콜은 null로 차단")
    void sanitizeUrl_data_프로토콜_차단() {
        // given
        HackerNewsItem item = createItem("data:text/html,<script>alert('xss')</script>");

        // when
        HackerNewsItemResponseDto dto = new HackerNewsItemResponseDto(item);

        // then
        assertThat(dto.getUrl()).isNull();
    }

    @Test
    @DisplayName("null URL은 null 반환")
    void sanitizeUrl_null_처리() {
        // given
        HackerNewsItem item = createItem(null);

        // when
        HackerNewsItemResponseDto dto = new HackerNewsItemResponseDto(item);

        // then
        assertThat(dto.getUrl()).isNull();
    }

    @Test
    @DisplayName("DTO 필드 매핑 정확성 검증")
    void DTO_필드_매핑() {
        // given
        HackerNewsItem item = HackerNewsItem.builder()
            .hnId(12345L)
            .title("Original Title")
            .translatedTitle("번역된 제목")
            .url("https://example.com")
            .author("testuser")
            .score(250)
            .commentCount(42)
            .hnCreatedAt(LocalDateTime.of(2024, 6, 15, 14, 30))
            .build();
        item.setId(1L);

        // when
        HackerNewsItemResponseDto dto = new HackerNewsItemResponseDto(item);

        // then
        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getHnId()).isEqualTo(12345L);
        assertThat(dto.getTitle()).isEqualTo("Original Title");
        assertThat(dto.getTranslatedTitle()).isEqualTo("번역된 제목");
        assertThat(dto.getUrl()).isEqualTo("https://example.com");
        assertThat(dto.getHnUrl()).isEqualTo("https://news.ycombinator.com/item?id=12345");
        assertThat(dto.getAuthor()).isEqualTo("testuser");
        assertThat(dto.getScore()).isEqualTo(250);
        assertThat(dto.getCommentCount()).isEqualTo(42);
        assertThat(dto.getHnCreatedAt()).isEqualTo("2024-06-15 14:30");
        assertThat(dto.getDetailUrl()).isEqualTo("/hackernews/1");
    }

    @Test
    @DisplayName("hnCreatedAt이 null이면 날짜 문자열도 null")
    void DTO_null_날짜() {
        // given
        HackerNewsItem item = HackerNewsItem.builder()
            .hnId(100L)
            .title("Title")
            .score(10)
            .commentCount(0)
            .build();
        item.setId(1L);

        // when
        HackerNewsItemResponseDto dto = new HackerNewsItemResponseDto(item);

        // then
        assertThat(dto.getHnCreatedAt()).isNull();
    }

    // ===== HackerNewsCommentResponseDto 테스트 =====

    @Test
    @DisplayName("CommentDTO 필드 매핑 정확성 검증")
    void commentDto_필드_매핑() {
        // given
        HackerNewsItem item = HackerNewsItem.builder()
            .hnId(100L)
            .title("Parent Item")
            .build();
        item.setId(1L);

        HackerNewsComment comment = HackerNewsComment.builder()
            .hnId(201L)
            .hackerNewsItem(item)
            .author("commenter")
            .textContent("This is the original English comment.")
            .translatedText("이것은 번역된 한국어 댓글입니다.")
            .depth(0)
            .hnCreatedAt(LocalDateTime.of(2024, 3, 10, 9, 15))
            .build();
        comment.setId(10L);

        // when
        HackerNewsCommentResponseDto dto = new HackerNewsCommentResponseDto(comment);

        // then
        assertThat(dto.getId()).isEqualTo(10L);
        assertThat(dto.getHnId()).isEqualTo(201L);
        assertThat(dto.getAuthor()).isEqualTo("commenter");
        assertThat(dto.getTextContent()).isEqualTo("This is the original English comment.");
        assertThat(dto.getTranslatedText()).isEqualTo("이것은 번역된 한국어 댓글입니다.");
        assertThat(dto.getDepth()).isEqualTo(0);
        assertThat(dto.getHnCreatedAt()).isEqualTo("2024-03-10 09:15");
    }

    @Test
    @DisplayName("CommentDTO: 번역 텍스트 null 허용")
    void commentDto_번역_null() {
        // given
        HackerNewsItem item = HackerNewsItem.builder()
            .hnId(100L)
            .title("Item")
            .build();
        item.setId(1L);

        HackerNewsComment comment = HackerNewsComment.builder()
            .hnId(301L)
            .hackerNewsItem(item)
            .author("user")
            .textContent("Comment without translation")
            .translatedText(null) // 번역 실패 또는 미번역
            .depth(1)
            .build();
        comment.setId(20L);

        // when
        HackerNewsCommentResponseDto dto = new HackerNewsCommentResponseDto(comment);

        // then
        assertThat(dto.getTextContent()).isEqualTo("Comment without translation");
        assertThat(dto.getTranslatedText()).isNull();
        assertThat(dto.getHnCreatedAt()).isNull();
    }

    // ===== Helper =====

    private HackerNewsItem createItem(String url) {
        HackerNewsItem item = HackerNewsItem.builder()
            .hnId(100L)
            .title("Test")
            .url(url)
            .author("user")
            .score(10)
            .commentCount(0)
            .build();
        item.setId(1L);
        return item;
    }
}
