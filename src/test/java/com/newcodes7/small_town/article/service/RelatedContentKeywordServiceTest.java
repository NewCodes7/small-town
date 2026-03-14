package com.newcodes7.small_town.article.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.repository.RelatedContentKeywordRepository;
import com.newcodes7.small_town.global.entity.RelatedContentKeyword;

/**
 * RelatedContentKeywordService 통합 테스트
 */
@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
@Transactional
public class RelatedContentKeywordServiceTest {

    @Autowired
    private RelatedContentKeywordService service;

    @Autowired
    private RelatedContentKeywordRepository repository;

    // --- getAllKeywords ---

    @Test
    @DisplayName("모든 키워드 조회 - 빈 목록")
    void getAllKeywords_Empty() {
        // when
        List<RelatedContentKeyword> result = service.getAllKeywords();

        // then
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("모든 키워드 조회 - 데이터 존재")
    void getAllKeywords_WithData() {
        // given
        service.addKeyword("테스트 추천 콘텐츠");
        service.addKeyword("test related posts");

        // when
        List<RelatedContentKeyword> result = service.getAllKeywords();

        // then
        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
    }

    // --- getAllKeywordStrings ---

    @Test
    @DisplayName("모든 키워드를 문자열 리스트로 조회")
    void getAllKeywordStrings() {
        // given
        service.addKeyword("테스트 추천 글");
        service.addKeyword("테스트 관련 글");

        // when
        List<String> result = service.getAllKeywordStrings();

        // then
        assertThat(result).contains("테스트 추천 글", "테스트 관련 글");
    }

    // --- addKeyword ---

    @Test
    @DisplayName("키워드 추가 - 성공")
    void addKeyword_Success() {
        // when
        RelatedContentKeyword result = service.addKeyword("새 키워드");

        // then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getKeyword()).isEqualTo("새 키워드");
    }

    @Test
    @DisplayName("키워드 추가 - 공백 포함 시 trim 처리")
    void addKeyword_Trimmed() {
        // when
        RelatedContentKeyword result = service.addKeyword("  공백 키워드  ");

        // then
        assertThat(result.getKeyword()).isEqualTo("공백 키워드");
    }

    @Test
    @DisplayName("키워드 추가 - null일 경우 예외")
    void addKeyword_Null_ThrowsException() {
        // when & then
        assertThatThrownBy(() -> service.addKeyword(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("비어있을 수 없습니다");
    }

    @Test
    @DisplayName("키워드 추가 - 빈 문자열일 경우 예외")
    void addKeyword_Empty_ThrowsException() {
        // when & then
        assertThatThrownBy(() -> service.addKeyword(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("키워드 추가 - 공백만 있는 경우 예외")
    void addKeyword_Whitespace_ThrowsException() {
        // when & then
        assertThatThrownBy(() -> service.addKeyword("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("키워드 추가 - 중복일 경우 예외")
    void addKeyword_Duplicate_ThrowsException() {
        // given
        service.addKeyword("중복 키워드");

        // when & then
        assertThatThrownBy(() -> service.addKeyword("중복 키워드"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("이미 존재하는 키워드");
    }

    // --- deleteKeyword ---

    @Test
    @DisplayName("키워드 삭제 - 성공")
    void deleteKeyword_Success() {
        // given
        RelatedContentKeyword keyword = service.addKeyword("삭제할 키워드");

        // when
        service.deleteKeyword(keyword.getId());

        // then
        assertThat(repository.findById(keyword.getId())).isEmpty();
    }

    @Test
    @DisplayName("키워드 삭제 - 존재하지 않는 ID")
    void deleteKeyword_NotFound() {
        // when & then
        assertThatThrownBy(() -> service.deleteKeyword(999999L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("찾을 수 없습니다");
    }

    // --- updateKeyword ---

    @Test
    @DisplayName("키워드 수정 - 성공")
    void updateKeyword_Success() {
        // given
        RelatedContentKeyword keyword = service.addKeyword("원래 키워드");

        // when
        RelatedContentKeyword updated = service.updateKeyword(keyword.getId(), "수정된 키워드");

        // then
        assertThat(updated.getKeyword()).isEqualTo("수정된 키워드");
    }

    @Test
    @DisplayName("키워드 수정 - null로 수정 시 예외")
    void updateKeyword_Null_ThrowsException() {
        // given
        RelatedContentKeyword keyword = service.addKeyword("테스트");

        // when & then
        assertThatThrownBy(() -> service.updateKeyword(keyword.getId(), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("키워드 수정 - 빈 문자열로 수정 시 예외")
    void updateKeyword_Empty_ThrowsException() {
        // given
        RelatedContentKeyword keyword = service.addKeyword("테스트2");

        // when & then
        assertThatThrownBy(() -> service.updateKeyword(keyword.getId(), ""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("키워드 수정 - 존재하지 않는 ID")
    void updateKeyword_NotFound() {
        // when & then
        assertThatThrownBy(() -> service.updateKeyword(999999L, "새 키워드"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("찾을 수 없습니다");
    }

    @Test
    @DisplayName("키워드 수정 - 다른 키워드와 중복")
    void updateKeyword_DuplicateWithOther() {
        // given
        service.addKeyword("기존 키워드");
        RelatedContentKeyword target = service.addKeyword("수정 대상");

        // when & then
        assertThatThrownBy(() -> service.updateKeyword(target.getId(), "기존 키워드"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("이미 존재하는 키워드");
    }

    @Test
    @DisplayName("키워드 수정 - 같은 값으로 수정 (자기 자신)")
    void updateKeyword_SameValue() {
        // given
        RelatedContentKeyword keyword = service.addKeyword("동일 키워드");

        // when
        RelatedContentKeyword updated = service.updateKeyword(keyword.getId(), "동일 키워드");

        // then
        assertThat(updated.getKeyword()).isEqualTo("동일 키워드");
    }

    @Test
    @DisplayName("키워드 수정 - trim 처리")
    void updateKeyword_Trimmed() {
        // given
        RelatedContentKeyword keyword = service.addKeyword("원래값");

        // when
        RelatedContentKeyword updated = service.updateKeyword(keyword.getId(), "  새값  ");

        // then
        assertThat(updated.getKeyword()).isEqualTo("새값");
    }
}
