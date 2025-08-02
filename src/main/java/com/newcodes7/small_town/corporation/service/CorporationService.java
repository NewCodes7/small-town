package com.newcodes7.small_town.corporation.service;

import com.newcodes7.small_town.corporation.dto.CorporationCreateDto;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.dto.CorporationUpdateDto;
import com.newcodes7.small_town.corporation.entity.Corporation;
import com.newcodes7.small_town.corporation.entity.CorporationIndustry;
import com.newcodes7.small_town.corporation.entity.Industry;
import com.newcodes7.small_town.corporation.exception.*;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.corporation.repository.IndustryRepository;
import com.newcodes7.small_town.crawler.entity.ParsingSelector;
import com.newcodes7.small_town.crawler.repository.ParsingSelectorRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorporationService {
    
    private final CorporationRepository corporationRepository;
    private final IndustryRepository industryRepository;
    private final FileUploadService fileUploadService;
    private final ParsingSelectorRepository parsingSelectorRepository;
    
    public Page<CorporationResponseDto> getAllCorporations(Pageable pageable) {
        return corporationRepository.findAllActive(pageable)
                .map(CorporationResponseDto::from);
    }
    
    public Page<CorporationResponseDto> searchCorporations(String name, Pageable pageable) {
        if (name == null || name.trim().isEmpty()) {
            throw new InvalidParameterException("name", name, "검색어는 비어있을 수 없습니다");
        }
        return corporationRepository.findByNameContainingAndDeletedAtIsNull(name, pageable)
                .map(CorporationResponseDto::from);
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
        
        Corporation corporation = Corporation.builder()
                .name(dto.getName())
                .isDomestic(dto.getIsDomestic())
                .homeLink(dto.getHomeLink())
                .blogLink(dto.getBlogLink())
                .crewLink(dto.getCrewLink())
                .logoUrl(dto.getLogoUrl())
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
        corporation.setHomeLink(dto.getHomeLink());
        corporation.setBlogLink(dto.getBlogLink());
        corporation.setCrewLink(dto.getCrewLink());
        corporation.setLogoUrl(dto.getLogoUrl());
        
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
        parsingSelectorRepository.save(parsingSelector);
        
        return CorporationResponseDto.from(corporation, parsingSelector);
    }
    
    @Transactional
    public void deleteCorporation(Long id) {
        if (id == null || id <= 0) {
            throw new InvalidParameterException("id", id);
        }
        Corporation corporation = corporationRepository.findActiveById(id)
                .orElseThrow(() -> new CorporationNotFoundException(id));
        corporation.softDelete();

        parsingSelectorRepository.deleteByCorporationId(id);
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
        
        Corporation corporation = Corporation.builder()
                .name(dto.getName())
                .isDomestic(dto.getIsDomestic())
                .homeLink(dto.getHomeLink())
                .blogLink(dto.getBlogLink())
                .crewLink(dto.getCrewLink())
                .logoUrl(dto.getLogoUrl())
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
        }
        
        // 기본 정보 업데이트
        if (dto.getHomeLink() != null) corporation.setHomeLink(dto.getHomeLink());
        if (dto.getBlogLink() != null) corporation.setBlogLink(dto.getBlogLink());
        if (dto.getCrewLink() != null) corporation.setCrewLink(dto.getCrewLink());
        if (dto.getLogoUrl() != null) corporation.setLogoUrl(dto.getLogoUrl());
        
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
        parsingSelectorRepository.save(parsingSelector);
        
        return CorporationResponseDto.from(corporation, parsingSelector);
    }
}