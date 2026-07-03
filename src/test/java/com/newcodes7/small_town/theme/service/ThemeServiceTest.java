package com.newcodes7.small_town.theme.service;

import static org.assertj.core.api.Assertions.*;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.theme.dto.ThemeCreateRequestDto;
import com.newcodes7.small_town.theme.dto.ThemeResponseDto;
import com.newcodes7.small_town.theme.dto.ThemeUpdateRequestDto;
import com.newcodes7.small_town.theme.entity.Theme;
import com.newcodes7.small_town.theme.repository.ThemeRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class ThemeServiceTest extends IntegrationTestBase {

    @Autowired
    private ThemeService themeService;

    @Autowired
    private ThemeRepository themeRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private CorporationRepository corporationRepository;

    private Corporation testCorporation;

    @BeforeEach
    void setUp() {
        // 테스트용 Corporation 생성
        testCorporation = Corporation.builder()
                .name("테스트회사")
                .isDomestic(1)
                .blogLink("https://test.com/blog")
                .build();
        testCorporation = corporationRepository.save(testCorporation);
    }

    @Test
    @DisplayName("활성화된 테마 목록 조회")
    void getActiveThemes() {
        // given
        Theme activeTheme = createTheme("활성 테마", true);
        Theme inactiveTheme = createTheme("비활성 테마", false);
        themeRepository.save(activeTheme);
        themeRepository.save(inactiveTheme);

        // when
        List<ThemeResponseDto> result = themeService.getActiveThemes();

        // then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("활성 테마");
    }

    @Test
    @DisplayName("전체 테마 목록 조회")
    void getAllThemes() {
        // given
        Theme theme1 = createTheme("테마1", true);
        Theme theme2 = createTheme("테마2", false);
        themeRepository.save(theme1);
        themeRepository.save(theme2);

        // when
        List<ThemeResponseDto> result = themeService.getAllThemes();

        // then
        assertThat(result).isNotNull();
        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("테마 생성")
    void createTheme() {
        // given
        ThemeCreateRequestDto dto = new ThemeCreateRequestDto();
        dto.setName("새로운 테마");
        dto.setDescription("테마 설명");
        dto.setEmoji("🎯");
        dto.setDisplayOrder(1);

        // when
        Theme result = themeService.createTheme(dto);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getName()).isEqualTo("새로운 테마");
        assertThat(result.getDescription()).isEqualTo("테마 설명");
        assertThat(result.getEmoji()).isEqualTo("🎯");
        assertThat(result.getIsActive()).isTrue();
    }

    @Test
    @DisplayName("테마 수정")
    void updateTheme() {
        // given
        Theme theme = createTheme("원래 테마", true);
        theme = themeRepository.save(theme);

        ThemeUpdateRequestDto dto = new ThemeUpdateRequestDto();
        dto.setName("수정된 테마");
        dto.setDescription("수정된 설명");
        dto.setEmoji("🔥");

        // when
        Theme result = themeService.updateTheme(theme.getId(), dto);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("수정된 테마");
        assertThat(result.getDescription()).isEqualTo("수정된 설명");
        assertThat(result.getEmoji()).isEqualTo("🔥");
    }

    @Test
    @DisplayName("테마 삭제 - Soft Delete")
    void deleteTheme() {
        // given
        Theme theme = createTheme("삭제할 테마", true);
        theme = themeRepository.save(theme);
        Long themeId = theme.getId();

        // when
        themeService.deleteTheme(themeId);

        // then
        Theme deletedTheme = themeRepository.findById(themeId).orElse(null);
        assertThat(deletedTheme).isNotNull();
        assertThat(deletedTheme.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("테마 수정 - 존재하지 않는 테마")
    void updateTheme_NotFound() {
        // given
        ThemeUpdateRequestDto dto = new ThemeUpdateRequestDto();
        dto.setName("수정된 테마");

        // when & then
        assertThatThrownBy(() -> themeService.updateTheme(999999L, dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("테마를 찾을 수 없습니다");
    }

    @Test
    @DisplayName("테마 삭제 - 존재하지 않는 테마")
    void deleteTheme_NotFound() {
        // when & then
        assertThatThrownBy(() -> themeService.deleteTheme(999999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("테마를 찾을 수 없습니다");
    }

    @Test
    @DisplayName("테마 상세 조회")
    void getThemeById() {
        // given
        Theme theme = createTheme("상세 조회 테마", true);
        theme = themeRepository.save(theme);

        // when
        ThemeResponseDto result = themeService.getThemeById(theme.getId());

        // then
        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("상세 조회 테마");
    }

    @Test
    @DisplayName("테마 상세 조회 - 존재하지 않는 테마")
    void getThemeById_NotFound() {
        // when & then
        assertThatThrownBy(() -> themeService.getThemeById(999999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("테마를 찾을 수 없습니다");
    }

    @Test
    @DisplayName("테마 정렬 순서 확인")
    void getActiveThemes_DisplayOrder() {
        // given
        Theme theme1 = createThemeWithOrder("테마1", true, 3);
        Theme theme2 = createThemeWithOrder("테마2", true, 1);
        Theme theme3 = createThemeWithOrder("테마3", true, 2);
        themeRepository.save(theme1);
        themeRepository.save(theme2);
        themeRepository.save(theme3);

        // when
        List<ThemeResponseDto> result = themeService.getActiveThemes();

        // then
        assertThat(result).isNotEmpty();
        // DisplayOrder 순으로 정렬되어야 함 (1, 2, 3)
        assertThat(result.get(0).getName()).isEqualTo("테마2");
        assertThat(result.get(1).getName()).isEqualTo("테마3");
        assertThat(result.get(2).getName()).isEqualTo("테마1");
    }

    private Theme createTheme(String name, boolean isActive) {
        return Theme.builder()
                .name(name)
                .description("테스트 설명")
                .emoji("📚")
                .displayOrder(0)
                .isActive(isActive)
                .build();
    }

    private Theme createThemeWithOrder(String name, boolean isActive, int displayOrder) {
        return Theme.builder()
                .name(name)
                .description("테스트 설명")
                .emoji("📚")
                .displayOrder(displayOrder)
                .isActive(isActive)
                .build();
    }
}
