package com.newcodes7.small_town.theme.service;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.global.util.KoreanCharacterUtil;
import com.newcodes7.small_town.theme.dto.*;
import com.newcodes7.small_town.theme.entity.Theme;
import com.newcodes7.small_town.theme.entity.ThemeArticle;
import com.newcodes7.small_town.theme.entity.ThemeVideo;
import com.newcodes7.small_town.theme.repository.ThemeArticleRepository;
import com.newcodes7.small_town.theme.repository.ThemeRepository;
import com.newcodes7.small_town.theme.repository.ThemeVideoRepository;
import com.newcodes7.small_town.video.repository.VideoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ThemeService {

    private final ThemeRepository themeRepository;
    private final ThemeArticleRepository themeArticleRepository;
    private final ThemeVideoRepository themeVideoRepository;
    private final ArticleRepository articleRepository;
    private final VideoRepository videoRepository;

    /**
     * 활성화된 테마 목록 조회 (공개용)
     */
    public List<ThemeResponseDto> getActiveThemes() {
        List<Theme> themes = themeRepository.findByIsActiveTrueAndDeletedAtIsNullOrderByDisplayOrderAsc();

        return themes.stream()
            .map(theme -> {
                int articleCount = (int) themeArticleRepository.countByThemeId(theme.getId());
                int videoCount = (int) themeVideoRepository.countByThemeId(theme.getId());
                String thumbnailUrl = getFirstContentThumbnail(theme.getId());
                return new ThemeResponseDto(theme, articleCount, videoCount, thumbnailUrl);
            })
            .collect(Collectors.toList());
    }

    /**
     * 전체 테마 목록 조회 (어드민용)
     */
    public List<ThemeResponseDto> getAllThemes() {
        List<Theme> themes = themeRepository.findByDeletedAtIsNullOrderByDisplayOrderAsc();

        return themes.stream()
            .map(theme -> {
                int articleCount = (int) themeArticleRepository.countByThemeId(theme.getId());
                int videoCount = (int) themeVideoRepository.countByThemeId(theme.getId());
                String thumbnailUrl = getFirstContentThumbnail(theme.getId());
                return new ThemeResponseDto(theme, articleCount, videoCount, thumbnailUrl);
            })
            .collect(Collectors.toList());
    }

    /**
     * 특정 테마 상세 조회 (컨텐츠 포함)
     */
    public ThemeResponseDto getThemeById(Long themeId) {
        Theme theme = themeRepository.findByIdAndDeletedAtIsNull(themeId)
            .orElseThrow(() -> new IllegalArgumentException("테마를 찾을 수 없습니다: " + themeId));

        // Article 목록 조회
        List<ThemeArticle> themeArticles = themeArticleRepository.findByThemeIdOrderByDisplayOrderAsc(themeId);
        List<ThemeContentDto> articleContents = themeArticles.stream()
            .filter(ta -> ta.getArticle().getDeletedAt() == null)
            .map(ta -> new ThemeContentDto(ta.getArticle(), ta.getDisplayOrder()))
            .collect(Collectors.toList());

        // Video 목록 조회
        List<ThemeVideo> themeVideos = themeVideoRepository.findByThemeIdOrderByDisplayOrderAsc(themeId);
        List<ThemeContentDto> videoContents = themeVideos.stream()
            .filter(tv -> tv.getVideo().getDeletedAt() == null)
            .map(tv -> new ThemeContentDto(tv.getVideo(), tv.getDisplayOrder()))
            .collect(Collectors.toList());

        // 두 리스트를 합쳐서 displayOrder로 정렬
        List<ThemeContentDto> allContents = new ArrayList<>();
        allContents.addAll(articleContents);
        allContents.addAll(videoContents);
        allContents.sort(Comparator.comparing(ThemeContentDto::getDisplayOrder));

        // 첫 번째 컨텐츠의 썸네일 추출
        String thumbnailUrl = allContents.isEmpty() ? null :
            (allContents.get(0).getArticle() != null ?
                allContents.get(0).getArticle().getThumbnailImage() :
                allContents.get(0).getVideo().getThumbnailUrl());

        return new ThemeResponseDto(theme, allContents, articleContents.size(), videoContents.size(), thumbnailUrl);
    }

    /**
     * 테마 생성 (어드민)
     */
    @Transactional
    public Theme createTheme(ThemeCreateRequestDto dto) {
        String decomposedName = KoreanCharacterUtil.decomposeHangul(dto.getName());

        Theme theme = Theme.builder()
            .name(dto.getName())
            .decomposedName(decomposedName)
            .description(dto.getDescription())
            .emoji(dto.getEmoji())
            .thumbnailUrl(dto.getThumbnailUrl())
            .displayOrder(dto.getDisplayOrder() != null ? dto.getDisplayOrder() : 0)
            .isActive(true)
            .build();

        Theme savedTheme = themeRepository.save(theme);
        log.info("테마 생성 완료 - ID: {}, Name: {}", savedTheme.getId(), savedTheme.getName());
        return savedTheme;
    }

    /**
     * 테마 수정 (어드민)
     */
    @Transactional
    public Theme updateTheme(Long themeId, ThemeUpdateRequestDto dto) {
        Theme theme = themeRepository.findByIdAndDeletedAtIsNull(themeId)
            .orElseThrow(() -> new IllegalArgumentException("테마를 찾을 수 없습니다: " + themeId));

        if (dto.getName() != null) {
            theme.setName(dto.getName());
            theme.setDecomposedName(KoreanCharacterUtil.decomposeHangul(dto.getName()));
        }
        if (dto.getDescription() != null) {
            theme.setDescription(dto.getDescription());
        }
        if (dto.getEmoji() != null) {
            theme.setEmoji(dto.getEmoji());
        }
        if (dto.getThumbnailUrl() != null) {
            theme.setThumbnailUrl(dto.getThumbnailUrl());
        }
        if (dto.getDisplayOrder() != null) {
            theme.setDisplayOrder(dto.getDisplayOrder());
        }
        if (dto.getIsActive() != null) {
            theme.setIsActive(dto.getIsActive());
        }

        Theme updatedTheme = themeRepository.save(theme);
        log.info("테마 수정 완료 - ID: {}, Name: {}", updatedTheme.getId(), updatedTheme.getName());
        return updatedTheme;
    }

    /**
     * 테마 삭제 (Soft Delete, 어드민)
     */
    @Transactional
    public void deleteTheme(Long themeId) {
        Theme theme = themeRepository.findByIdAndDeletedAtIsNull(themeId)
            .orElseThrow(() -> new IllegalArgumentException("테마를 찾을 수 없습니다: " + themeId));

        theme.softDelete();
        themeRepository.save(theme);
        log.info("테마 삭제 완료 - ID: {}, Name: {}", theme.getId(), theme.getName());
    }

    /**
     * 테마에 아티클 추가 (어드민)
     */
    @Transactional
    public ThemeArticle addArticleToTheme(Long themeId, ThemeArticleAddRequestDto dto) {
        Theme theme = themeRepository.findByIdAndDeletedAtIsNull(themeId)
            .orElseThrow(() -> new IllegalArgumentException("테마를 찾을 수 없습니다: " + themeId));

        Article article = articleRepository.findById(dto.getArticleId())
            .orElseThrow(() -> new IllegalArgumentException("아티클을 찾을 수 없습니다: " + dto.getArticleId()));

        // 이미 추가된 아티클인지 확인
        Optional<ThemeArticle> existing = themeArticleRepository.findByThemeIdAndArticleId(themeId, dto.getArticleId());
        if (existing.isPresent()) {
            throw new IllegalArgumentException("이미 테마에 추가된 아티클입니다");
        }

        ThemeArticle themeArticle = ThemeArticle.builder()
            .theme(theme)
            .article(article)
            .displayOrder(dto.getDisplayOrder() != null ? dto.getDisplayOrder() : 0)
            .build();

        ThemeArticle savedThemeArticle = themeArticleRepository.save(themeArticle);
        log.info("테마에 아티클 추가 완료 - ThemeID: {}, ArticleID: {}", themeId, dto.getArticleId());
        return savedThemeArticle;
    }

    /**
     * 테마에서 아티클 제거 (어드민)
     */
    @Transactional
    public void removeArticleFromTheme(Long themeId, Long articleId) {
        themeArticleRepository.deleteByThemeIdAndArticleId(themeId, articleId);
        log.info("테마에서 아티클 제거 완료 - ThemeID: {}, ArticleID: {}", themeId, articleId);
    }

    /**
     * 테마에 비디오 추가 (어드민)
     */
    @Transactional
    public ThemeVideo addVideoToTheme(Long themeId, ThemeVideoAddRequestDto dto) {
        Theme theme = themeRepository.findByIdAndDeletedAtIsNull(themeId)
            .orElseThrow(() -> new IllegalArgumentException("테마를 찾을 수 없습니다: " + themeId));

        Video video = videoRepository.findById(dto.getVideoId())
            .orElseThrow(() -> new IllegalArgumentException("비디오를 찾을 수 없습니다: " + dto.getVideoId()));

        // 이미 추가된 비디오인지 확인
        Optional<ThemeVideo> existing = themeVideoRepository.findByThemeIdAndVideoId(themeId, dto.getVideoId());
        if (existing.isPresent()) {
            throw new IllegalArgumentException("이미 테마에 추가된 비디오입니다");
        }

        ThemeVideo themeVideo = ThemeVideo.builder()
            .theme(theme)
            .video(video)
            .displayOrder(dto.getDisplayOrder() != null ? dto.getDisplayOrder() : 0)
            .build();

        ThemeVideo savedThemeVideo = themeVideoRepository.save(themeVideo);
        log.info("테마에 비디오 추가 완료 - ThemeID: {}, VideoID: {}", themeId, dto.getVideoId());
        return savedThemeVideo;
    }

    /**
     * 테마에서 비디오 제거 (어드민)
     */
    @Transactional
    public void removeVideoFromTheme(Long themeId, Long videoId) {
        themeVideoRepository.deleteByThemeIdAndVideoId(themeId, videoId);
        log.info("테마에서 비디오 제거 완료 - ThemeID: {}, VideoID: {}", themeId, videoId);
    }

    /**
     * 테마 내 컨텐츠 순서 변경 (어드민)
     * contentIds 형식: "article:123", "video:456" 등
     */
    @Transactional
    public void reorderContentsInTheme(Long themeId, List<String> contentIds) {
        for (int i = 0; i < contentIds.size(); i++) {
            final int displayOrder = i;  // Lambda에서 사용하기 위해 final 변수로 선언
            String contentId = contentIds.get(i);
            String[] parts = contentId.split(":");

            if (parts.length != 2) {
                log.warn("잘못된 contentId 형식: {}", contentId);
                continue;
            }

            String type = parts[0];  // "article" or "video"
            Long id = Long.parseLong(parts[1]);

            if ("article".equals(type)) {
                Optional<ThemeArticle> themeArticle = themeArticleRepository.findByThemeIdAndArticleId(themeId, id);
                themeArticle.ifPresent(ta -> {
                    ta.setDisplayOrder(displayOrder);
                    themeArticleRepository.save(ta);
                });
            } else if ("video".equals(type)) {
                Optional<ThemeVideo> themeVideo = themeVideoRepository.findByThemeIdAndVideoId(themeId, id);
                themeVideo.ifPresent(tv -> {
                    tv.setDisplayOrder(displayOrder);
                    themeVideoRepository.save(tv);
                });
            }
        }
        log.info("테마 컨텐츠 순서 변경 완료 - ThemeID: {}", themeId);
    }

    /**
     * 첫 번째 컨텐츠의 썸네일 URL 가져오기 (최적화 버전)
     * displayOrder가 가장 작은 컨텐츠의 썸네일 URL만 직접 조회 (엔티티 로딩 없음)
     */
    private String getFirstContentThumbnail(Long themeId) {
        // Article 썸네일 조회
        Optional<String> articleThumbnail = themeArticleRepository.findFirstThumbnailByThemeId(themeId);

        // Video 썸네일 조회
        Optional<String> videoThumbnail = themeVideoRepository.findFirstThumbnailByThemeId(themeId);

        // Article 썸네일이 있으면 반환 (Article이 Video보다 우선)
        if (articleThumbnail.isPresent()) {
            return articleThumbnail.get();
        }

        // Video 썸네일이 있으면 반환
        if (videoThumbnail.isPresent()) {
            return videoThumbnail.get();
        }

        // 둘 다 없으면 null
        return null;
    }
}
