package com.newcodes7.small_town.corporation.service;

import java.io.IOException;
import java.util.List;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.newcodes7.small_town.global.annotation.CachePreload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.newcodes7.small_town.corporation.dto.CorporationCreateDto;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.dto.CorporationUpdateDto;
import com.newcodes7.small_town.corporation.entity.CorporationIndustry;
import com.newcodes7.small_town.corporation.entity.Industry;
import com.newcodes7.small_town.corporation.exception.CorporationNotFoundException;
import com.newcodes7.small_town.corporation.exception.DuplicateCorporationNameException;
import com.newcodes7.small_town.corporation.exception.IndustryNotFoundException;
import com.newcodes7.small_town.corporation.exception.InvalidParameterException;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.corporation.repository.CorporationSpecification;
import com.newcodes7.small_town.corporation.repository.IndustryRepository;
import com.newcodes7.small_town.crawler.entity.ParsingSelector;
import com.newcodes7.small_town.crawler.repository.ParsingSelectorRepository;
import com.newcodes7.small_town.crawler.integration.youtube.YouTubeService;
import com.newcodes7.small_town.global.cache.NginxCachePurgeService;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.util.KoreanCharacterUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorporationService {
    
    private final CorporationRepository corporationRepository;
    private final IndustryRepository industryRepository;
    private final FileUploadService fileUploadService;
    private final ParsingSelectorRepository parsingSelectorRepository;
    private final NginxCachePurgeService nginxCachePurgeService;
    private final YouTubeService youtubeService;
    
    /**
     * 통합 필터링 메서드
     * 검색어, 필터 타입(blog/youtube/all), Industry 필터를 하나의 쿼리로 처리합니다.
     *
     * @param search 검색어 (null 가능)
     * @param filter 필터 타입 ("all", "blog", "youtube", null)
     * @param industryIds Industry ID 리스트 (null 또는 빈 리스트 가능)
     * @param pageable 페이징 정보
     * @return 필터링된 Corporation 페이지
     */
    public Page<CorporationResponseDto> getCorporationsWithFilters(String search, String filter, List<Integer> industryIds, Pageable pageable) {
        return corporationRepository.findAll(
                CorporationSpecification.withFilters(search, filter, industryIds),
                pageable
        ).map(CorporationResponseDto::from);
    }

    public CorporationResponseDto getCorporationById(Long id) {
        if (id == null || id <= 0) {
            throw new InvalidParameterException("id", id);
        }
        Corporation corporation = corporationRepository.findActiveById(id)
                .orElseThrow(() -> new CorporationNotFoundException(id));
        ParsingSelector parsingSelector = parsingSelectorRepository.findByCorporationIdOrDefault(corporation.getId());
        return CorporationResponseDto.from(corporation, parsingSelector);
    }
    
    @Transactional
    public CorporationResponseDto createCorporation(CorporationCreateDto dto) {
        if (dto == null) {
            throw new InvalidParameterException("dto", null);
        }
        if (dto.getName() == null || dto.getName().trim().isEmpty()) {
            throw new InvalidParameterException("name", dto.getName(), "기업명은 필수입니다");
        }

        if (corporationRepository.existsByNameAndDeletedAtIsNull(dto.getName())) {
            throw new DuplicateCorporationNameException(dto.getName());
        }

        // YouTube URL 처리
        String youtubeChannelId = null;
        if (dto.getYoutubeUrl() != null && !dto.getYoutubeUrl().trim().isEmpty()) {
            try {
                youtubeChannelId = youtubeService.getChannelIdFromUrl(dto.getYoutubeUrl());
                if (youtubeChannelId == null) {
                    log.warn("YouTube Channel ID 조회 실패 - URL: {}", dto.getYoutubeUrl());
                }
            } catch (Exception e) {
                log.error("YouTube Channel ID 조회 중 오류 발생 - URL: {}", dto.getYoutubeUrl(), e);
            }
        }

        // 기업명 자모 분리
        String decomposedName = KoreanCharacterUtil.decomposeHangul(dto.getName());
        String chosungName = KoreanCharacterUtil.extractChosung(dto.getName());

        // 대체 기업명 자모 분리
        String decomposedAlternateName = null;
        String chosungAlternateName = null;
        if (dto.getAlternateName() != null && !dto.getAlternateName().trim().isEmpty()) {
            decomposedAlternateName = KoreanCharacterUtil.decomposeHangul(dto.getAlternateName());
            chosungAlternateName = KoreanCharacterUtil.extractChosung(dto.getAlternateName());
        }

        Corporation corporation = Corporation.builder()
                .name(dto.getName())
                .alternateName(dto.getAlternateName())
                .decomposedName(decomposedName)
                .chosungName(chosungName)
                .decomposedAlternateName(decomposedAlternateName)
                .chosungAlternateName(chosungAlternateName)
                .isDomestic(dto.getIsDomestic())
                .homeLink(dto.getHomeLink())
                .blogLink(dto.getBlogLink())
                .crewLink(dto.getCrewLink())
                .logoUrl(dto.getLogoUrl())
                .youtubeUrl(dto.getYoutubeUrl())
                .youtubeChannelId(youtubeChannelId)
                .build();

        Corporation savedCorporation = corporationRepository.save(corporation);

        ParsingSelector parsingSelector = ParsingSelector.builder()
                .corporationId(corporation.getId())
                .baseUrl(dto.getBaseUrl())
                .article(dto.getArticle())
                .title(dto.getTitle())
                .link(dto.getLink())
                .thumbnail(dto.getThumbnail())
                .publish(dto.getPublish())
                .publishFormat(dto.getPublishFormat())
                .publishType(dto.getPublishType() != null ? dto.getPublishType() : "OUTER")
                .innerPublishSelector(dto.getInnerPublishSelector())
                .paginationType(dto.getPaginationType())
                .pageUrlPattern(dto.getPageUrlPattern())
                .nextPageSelector(dto.getNextPageSelector())
                .maxPages(dto.getMaxPages())
                .build();

        parsingSelectorRepository.save(parsingSelector);

        // 업종 관계 설정
        if (dto.getIndustryIds() != null && !dto.getIndustryIds().isEmpty()) {
            List<Industry> industries = industryRepository.findAllById(dto.getIndustryIds());
            if (industries.size() != dto.getIndustryIds().size()) {
                throw new IndustryNotFoundException(dto.getIndustryIds());
            }
            for (Industry industry : industries) {
                CorporationIndustry corporationIndustry = CorporationIndustry.builder()
                        .corporation(savedCorporation)
                        .industry(industry)
                        .build();
                savedCorporation.getCorporationIndustries().add(corporationIndustry);
            }
        }

        return CorporationResponseDto.from(savedCorporation);
    }

    @Transactional
    @CacheEvict(value = "corporationArticles", allEntries = true)
    @CachePreload
    public CorporationResponseDto updateCorporation(Long id, CorporationUpdateDto dto) {
        if (id == null || id <= 0) {
            throw new InvalidParameterException("id", id);
        }
        if (dto == null) {
            throw new InvalidParameterException("dto", null);
        }
        if (dto.getName() == null || dto.getName().trim().isEmpty()) {
            throw new InvalidParameterException("name", dto.getName(), "기업명은 필수입니다");
        }
        
        Corporation corporation = corporationRepository.findActiveById(id)
                .orElseThrow(() -> new CorporationNotFoundException(id));
        
        // 다른 기업이 같은 이름을 사용하는지 확인
        if (!corporation.getName().equals(dto.getName()) && 
            corporationRepository.existsByNameAndDeletedAtIsNull(dto.getName())) {
            throw new DuplicateCorporationNameException(dto.getName());
        }
        
        corporation.setName(dto.getName());
        corporation.setAlternateName(dto.getAlternateName());

        // 기업명 자모 분리
        corporation.setDecomposedName(KoreanCharacterUtil.decomposeHangul(dto.getName()));
        corporation.setChosungName(KoreanCharacterUtil.extractChosung(dto.getName()));

        // 대체 기업명 자모 분리
        if (dto.getAlternateName() != null && !dto.getAlternateName().trim().isEmpty()) {
            corporation.setDecomposedAlternateName(KoreanCharacterUtil.decomposeHangul(dto.getAlternateName()));
            corporation.setChosungAlternateName(KoreanCharacterUtil.extractChosung(dto.getAlternateName()));
        } else {
            corporation.setDecomposedAlternateName(null);
            corporation.setChosungAlternateName(null);
        }

        corporation.setHomeLink(dto.getHomeLink());
        corporation.setBlogLink(dto.getBlogLink());
        corporation.setCrewLink(dto.getCrewLink());
        corporation.setLogoUrl(dto.getLogoUrl());

        // YouTube URL 처리
        if (dto.getYoutubeUrl() != null && !dto.getYoutubeUrl().trim().isEmpty()) {
            try {
                String youtubeChannelId = youtubeService.getChannelIdFromUrl(dto.getYoutubeUrl());
                corporation.setYoutubeUrl(dto.getYoutubeUrl());
                corporation.setYoutubeChannelId(youtubeChannelId);
                if (youtubeChannelId == null) {
                    log.warn("YouTube Channel ID 조회 실패 - URL: {}", dto.getYoutubeUrl());
                }
            } catch (Exception e) {
                log.error("YouTube Channel ID 조회 중 오류 발생 - URL: {}", dto.getYoutubeUrl(), e);
            }
        } else {
            // YouTube URL이 null이거나 비어있으면 초기화
            corporation.setYoutubeUrl(null);
            corporation.setYoutubeChannelId(null);
        }

        // 기존 업종 관계 제거
        corporation.getCorporationIndustries().clear();
        
        // 새로운 업종 관계 설정
        if (dto.getIndustryIds() != null && !dto.getIndustryIds().isEmpty()) {
            List<Industry> industries = industryRepository.findAllById(dto.getIndustryIds());
            if (industries.size() != dto.getIndustryIds().size()) {
                throw new IndustryNotFoundException(dto.getIndustryIds());
            }
            for (Industry industry : industries) {
                CorporationIndustry corporationIndustry = CorporationIndustry.builder()
                        .corporation(corporation)
                        .industry(industry)
                        .build();
                corporation.getCorporationIndustries().add(corporationIndustry);
            }
        }

        ParsingSelector parsingSelector = parsingSelectorRepository.findByCorporationIdOrDefault(corporation.getId());
        parsingSelector.setBaseUrl(dto.getBaseUrl());
        parsingSelector.setArticle(dto.getArticle());
        parsingSelector.setTitle(dto.getTitle());
        parsingSelector.setLink(dto.getLink());
        parsingSelector.setThumbnail(dto.getThumbnail());
        parsingSelector.setPublish(dto.getPublish());
        parsingSelector.setPublishFormat(dto.getPublishFormat());
        parsingSelector.setPublishType(dto.getPublishType() != null ? dto.getPublishType() : "OUTER");
        parsingSelector.setInnerPublishSelector(dto.getInnerPublishSelector());
        parsingSelector.setPaginationType(dto.getPaginationType());
        parsingSelector.setPageUrlPattern(dto.getPageUrlPattern());
        parsingSelector.setNextPageSelector(dto.getNextPageSelector());
        parsingSelector.setMaxPages(dto.getMaxPages());
        parsingSelectorRepository.save(parsingSelector);

        // Nginx 캐시 purge
        purgeCorporationCache(id);

        return CorporationResponseDto.from(corporation, parsingSelector);
    }

    @Transactional
    @CacheEvict(value = "corporationArticles", allEntries = true)
    @CachePreload
    public void deleteCorporation(Long id) {
        if (id == null || id <= 0) {
            throw new InvalidParameterException("id", id);
        }
        Corporation corporation = corporationRepository.findActiveById(id)
                .orElseThrow(() -> new CorporationNotFoundException(id));
        corporation.softDelete();

        parsingSelectorRepository.deleteByCorporationId(id);

        // Nginx 캐시 purge
        purgeCorporationCache(id);
    }
    
    public long getTotalCorporationCount() {
        return corporationRepository.countByDeletedAtIsNull();
    }
    
    /**
     * 파일 업로드와 함께 회사를 생성합니다.
     */
    @Transactional
    public CorporationResponseDto createCorporationWithLogo(CorporationCreateDto dto, MultipartFile logoFile) throws IOException {
        if (dto == null) {
            throw new InvalidParameterException("dto", null);
        }
        if (dto.getName() == null || dto.getName().trim().isEmpty()) {
            throw new InvalidParameterException("name", dto.getName(), "기업명은 필수입니다");
        }

        if (corporationRepository.existsByNameAndDeletedAtIsNull(dto.getName())) {
            throw new DuplicateCorporationNameException(dto.getName());
        }

        // YouTube URL 처리
        String youtubeChannelId = null;
        if (dto.getYoutubeUrl() != null && !dto.getYoutubeUrl().trim().isEmpty()) {
            try {
                youtubeChannelId = youtubeService.getChannelIdFromUrl(dto.getYoutubeUrl());
                if (youtubeChannelId == null) {
                    log.warn("YouTube Channel ID 조회 실패 - URL: {}", dto.getYoutubeUrl());
                }
            } catch (Exception e) {
                log.error("YouTube Channel ID 조회 중 오류 발생 - URL: {}", dto.getYoutubeUrl(), e);
            }
        }

        // 기업명 자모 분리
        String decomposedName = KoreanCharacterUtil.decomposeHangul(dto.getName());
        String chosungName = KoreanCharacterUtil.extractChosung(dto.getName());

        // 대체 기업명 자모 분리
        String decomposedAlternateName = null;
        String chosungAlternateName = null;
        if (dto.getAlternateName() != null && !dto.getAlternateName().trim().isEmpty()) {
            decomposedAlternateName = KoreanCharacterUtil.decomposeHangul(dto.getAlternateName());
            chosungAlternateName = KoreanCharacterUtil.extractChosung(dto.getAlternateName());
        }

        Corporation corporation = Corporation.builder()
                .name(dto.getName())
                .alternateName(dto.getAlternateName())
                .decomposedName(decomposedName)
                .chosungName(chosungName)
                .decomposedAlternateName(decomposedAlternateName)
                .chosungAlternateName(chosungAlternateName)
                .isDomestic(dto.getIsDomestic())
                .homeLink(dto.getHomeLink())
                .blogLink(dto.getBlogLink())
                .crewLink(dto.getCrewLink())
                .logoUrl(dto.getLogoUrl())
                .youtubeUrl(dto.getYoutubeUrl())
                .youtubeChannelId(youtubeChannelId)
                .build();

        Corporation savedCorporation = corporationRepository.save(corporation);

        ParsingSelector parsingSelector = ParsingSelector.builder()
                .corporationId(corporation.getId())
                .baseUrl(dto.getBaseUrl())
                .article(dto.getArticle())
                .title(dto.getTitle())
                .link(dto.getLink())
                .thumbnail(dto.getThumbnail())
                .publish(dto.getPublish())
                .publishFormat(dto.getPublishFormat())
                .publishType(dto.getPublishType() != null ? dto.getPublishType() : "OUTER")
                .innerPublishSelector(dto.getInnerPublishSelector())
                .paginationType(dto.getPaginationType())
                .pageUrlPattern(dto.getPageUrlPattern())
                .nextPageSelector(dto.getNextPageSelector())
                .maxPages(dto.getMaxPages())
                .build();

        parsingSelectorRepository.save(parsingSelector);

        // 로고 파일 업로드 처리
        if (logoFile != null && !logoFile.isEmpty()) {
            try {
                // S3에 업로드 시도
                String logoS3Url = fileUploadService.saveLogoFileToS3(logoFile, savedCorporation.getName());
                savedCorporation.setLogoS3Url(logoS3Url);
                log.info("회사 로고 S3 업로드 완료: {} -> {}", savedCorporation.getName(), logoS3Url);
            } catch (Exception e) {
                log.warn("S3 업로드 실패, 로컬 저장으로 대체: {}", savedCorporation.getName(), e);
                try {
                    // S3 업로드 실패 시 로컬 저장
                    String logoFilename = fileUploadService.saveLogoFile(logoFile, savedCorporation.getId());
                    savedCorporation.setLogoFilename(logoFilename);
                    log.info("회사 로고 로컬 업로드 완료: {} -> {}", savedCorporation.getName(), logoFilename);
                } catch (IOException localE) {
                    log.error("회사 로고 업로드 실패: {}", savedCorporation.getName(), localE);
                    throw new RuntimeException("로고 파일 업로드에 실패했습니다: " + localE.getMessage(), localE);
                }
            }
        }
        
        // 업종 관계 설정
        if (dto.getIndustryIds() != null && !dto.getIndustryIds().isEmpty()) {
            List<Industry> industries = industryRepository.findAllById(dto.getIndustryIds());
            if (industries.size() != dto.getIndustryIds().size()) {
                throw new IndustryNotFoundException(dto.getIndustryIds());
            }
            for (Industry industry : industries) {
                CorporationIndustry corporationIndustry = CorporationIndustry.builder()
                        .corporation(savedCorporation)
                        .industry(industry)
                        .build();
                savedCorporation.getCorporationIndustries().add(corporationIndustry);
            }
        }
        
        return CorporationResponseDto.from(savedCorporation);
    }
    
    /**
     * 파일 업로드와 함께 회사 정보를 업데이트합니다.
     */
    @Transactional
    @CacheEvict(value = "corporationArticles", allEntries = true)
    @CachePreload
    public CorporationResponseDto updateCorporationWithLogo(Long id, CorporationUpdateDto dto, MultipartFile logoFile) throws IOException {
        if (id == null || id <= 0) {
            throw new InvalidParameterException("id", id);
        }
        if (dto == null) {
            throw new InvalidParameterException("dto", null);
        }
        
        Corporation corporation = corporationRepository.findActiveById(id)
                .orElseThrow(() -> new CorporationNotFoundException(id));
        
        // 이름 중복 검사 (자기 자신 제외)
        if (dto.getName() != null && !dto.getName().trim().isEmpty()) {
            if (!corporation.getName().equals(dto.getName()) &&
                corporationRepository.existsByNameAndDeletedAtIsNull(dto.getName())) {
                throw new DuplicateCorporationNameException(dto.getName());
            }
            corporation.setName(dto.getName());

            // 기업명 자모 분리
            corporation.setDecomposedName(KoreanCharacterUtil.decomposeHangul(dto.getName()));
            corporation.setChosungName(KoreanCharacterUtil.extractChosung(dto.getName()));
        }

        // 대체 기업명 업데이트
        corporation.setAlternateName(dto.getAlternateName());
        if (dto.getAlternateName() != null && !dto.getAlternateName().trim().isEmpty()) {
            corporation.setDecomposedAlternateName(KoreanCharacterUtil.decomposeHangul(dto.getAlternateName()));
            corporation.setChosungAlternateName(KoreanCharacterUtil.extractChosung(dto.getAlternateName()));
        } else {
            corporation.setDecomposedAlternateName(null);
            corporation.setChosungAlternateName(null);
        }

        // 기본 정보 업데이트
        if (dto.getHomeLink() != null) corporation.setHomeLink(dto.getHomeLink());
        if (dto.getBlogLink() != null) corporation.setBlogLink(dto.getBlogLink());
        if (dto.getCrewLink() != null) corporation.setCrewLink(dto.getCrewLink());
        if (dto.getLogoUrl() != null) corporation.setLogoUrl(dto.getLogoUrl());

        // YouTube URL 처리
        if (dto.getYoutubeUrl() != null) {
            if (!dto.getYoutubeUrl().trim().isEmpty()) {
                try {
                    String youtubeChannelId = youtubeService.getChannelIdFromUrl(dto.getYoutubeUrl());
                    corporation.setYoutubeUrl(dto.getYoutubeUrl());
                    corporation.setYoutubeChannelId(youtubeChannelId);
                    if (youtubeChannelId == null) {
                        log.warn("YouTube Channel ID 조회 실패 - URL: {}", dto.getYoutubeUrl());
                    }
                } catch (Exception e) {
                    log.error("YouTube Channel ID 조회 중 오류 발생 - URL: {}", dto.getYoutubeUrl(), e);
                }
            } else {
                // YouTube URL이 빈 문자열이면 초기화
                corporation.setYoutubeUrl(null);
                corporation.setYoutubeChannelId(null);
            }
        }

        // 로고 파일 업로드 처리
        if (logoFile != null && !logoFile.isEmpty()) {
            // 기존 로고 파일 삭제
            if (corporation.getLogoFilename() != null) {
                fileUploadService.deleteLogoFile(corporation.getLogoFilename());
            }
            
            try {
                // S3에 업로드 시도
                String logoS3Url = fileUploadService.saveLogoFileToS3(logoFile, corporation.getName());
                corporation.setLogoS3Url(logoS3Url);
                corporation.setLogoFilename(null); // S3 사용 시 로컬 파일명 제거
                log.info("회사 로고 S3 업데이트 완료: {} -> {}", corporation.getName(), logoS3Url);
            } catch (Exception e) {
                log.warn("S3 업로드 실패, 로컬 저장으로 대체: {}", corporation.getName(), e);
                try {
                    // S3 업로드 실패 시 로컬 저장
                    String logoFilename = fileUploadService.saveLogoFile(logoFile, corporation.getId());
                    corporation.setLogoFilename(logoFilename);
                    corporation.setLogoS3Url(null); // 로컬 사용 시 S3 URL 제거
                    log.info("회사 로고 로컬 업데이트 완료: {} -> {}", corporation.getName(), logoFilename);
                } catch (IOException localE) {
                    log.error("회사 로고 업데이트 실패: {}", corporation.getName(), localE);
                    throw new RuntimeException("로고 파일 업데이트에 실패했습니다: " + localE.getMessage(), localE);
                }
            }
        }
        
        // 업종 관계 업데이트
        if (dto.getIndustryIds() != null) {
            corporation.getCorporationIndustries().clear();
            if (!dto.getIndustryIds().isEmpty()) {
                List<Industry> industries = industryRepository.findAllById(dto.getIndustryIds());
                if (industries.size() != dto.getIndustryIds().size()) {
                    throw new IndustryNotFoundException(dto.getIndustryIds());
                }
                for (Industry industry : industries) {
                    CorporationIndustry corporationIndustry = CorporationIndustry.builder()
                            .corporation(corporation)
                            .industry(industry)
                            .build();
                    corporation.getCorporationIndustries().add(corporationIndustry);
                }
            }
        }

        ParsingSelector parsingSelector = parsingSelectorRepository.findByCorporationIdOrDefault(corporation.getId());
        parsingSelector.setBaseUrl(dto.getBaseUrl());
        parsingSelector.setArticle(dto.getArticle());
        parsingSelector.setTitle(dto.getTitle());
        parsingSelector.setLink(dto.getLink());
        parsingSelector.setThumbnail(dto.getThumbnail());
        parsingSelector.setPublish(dto.getPublish());
        parsingSelector.setPublishFormat(dto.getPublishFormat());
        parsingSelector.setPublishType(dto.getPublishType() != null ? dto.getPublishType() : "OUTER");
        parsingSelector.setInnerPublishSelector(dto.getInnerPublishSelector());
        parsingSelector.setPaginationType(dto.getPaginationType());
        parsingSelector.setPageUrlPattern(dto.getPageUrlPattern());
        parsingSelector.setNextPageSelector(dto.getNextPageSelector());
        parsingSelector.setMaxPages(dto.getMaxPages());
        parsingSelectorRepository.save(parsingSelector);

        // Nginx 캐시 purge
        purgeCorporationCache(id);

        return CorporationResponseDto.from(corporation, parsingSelector);
    }

    /**
     * 블로그가 있는 기업 검색 (자동완성용)
     * 최적화된 EXISTS 쿼리 사용 (601ms → 1.6ms, 375배 개선)
     */
    public List<Corporation> searchCorporationsWithArticles(String query, int limit) {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }
        return corporationRepository.findCorporationsWithArticlesByNameOptimized(
                query.trim(),
                PageRequest.of(0, limit)
        );
    }

    /**
     * 비디오가 있는 기업 검색 (자동완성용)
     */
    public List<Corporation> searchCorporationsWithVideos(String query, int limit) {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }
        return corporationRepository.findCorporationsWithVideosByNameContaining(
                query.trim(),
                PageRequest.of(0, limit)
        );
    }

    /**
     * 특정 기업의 모든 글 삭제 (소프트 삭제)
     */
    @Transactional
    @CacheEvict(value = "corporationArticles", allEntries = true)
    @CachePreload
    public void deleteAllArticles(Long corporationId) {
        if (corporationId == null || corporationId <= 0) {
            throw new InvalidParameterException("corporationId", corporationId);
        }

        Corporation corporation = corporationRepository.findActiveById(corporationId)
                .orElseThrow(() -> new CorporationNotFoundException(corporationId));

        // 해당 기업의 모든 글을 조회하고 소프트 삭제
        List<com.newcodes7.small_town.global.entity.Article> articles =
            corporation.getArticles().stream()
                .filter(article -> article.getDeletedAt() == null)
                .collect(java.util.stream.Collectors.toList());

        log.info("기업 {} 의 글 삭제 시작 - 총 {}개", corporation.getName(), articles.size());

        for (com.newcodes7.small_town.global.entity.Article article : articles) {
            article.softDelete();
        }

        log.info("기업 {} 의 글 삭제 완료 - 총 {}개", corporation.getName(), articles.size());

        // Nginx 캐시 purge
        purgeCorporationCache(corporationId);
    }

    /**
     * Corporation 관련 Nginx 캐시를 purge합니다.
     * - 해당 corporation 상세 페이지 캐시 삭제
     * - home 페이지 캐시 삭제 (기업 목록에 영향)
     */
    private void purgeCorporationCache(Long corporationId) {
        try {
            log.info("Corporation 캐시 purge 시작 - ID: {}", corporationId);

            // Corporation 상세 페이지 캐시 삭제
            nginxCachePurgeService.purgeCorporationPage(corporationId);

            // Home 페이지 캐시 삭제 (기업 목록에 영향)
            nginxCachePurgeService.purgeHomePages();

            log.info("Corporation 캐시 purge 완료 - ID: {}", corporationId);
        } catch (Exception e) {
            log.error("Corporation 캐시 purge 실패 - ID: {}, 오류: {}", corporationId, e.getMessage(), e);
            // 캐시 purge 실패는 비즈니스 로직을 실패시키지 않음
        }
    }
}