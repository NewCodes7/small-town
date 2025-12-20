package com.newcodes7.small_town.admin.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.dto.CorporationUpdateDto;
import com.newcodes7.small_town.corporation.entity.Industry;
import com.newcodes7.small_town.corporation.repository.IndustryRepository;
import com.newcodes7.small_town.corporation.service.CorporationService;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin Industry 관리 비즈니스 로직
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AdminIndustryService {

    private final IndustryRepository industryRepository;
    private final CorporationService corporationService;

    /**
     * Industry 삭제 결과 DTO
     */
    @Getter
    @Builder
    public static class IndustryDeleteResult {
        private String industryName;
        private int affectedCorporationCount;
    }

    /**
     * 모든 Industry 목록 조회
     */
    public List<Industry> getAllIndustries() {
        return industryRepository.findAll();
    }

    /**
     * Industry 생성
     *
     * @param name Industry 이름
     * @return 생성된 Industry
     * @throws IllegalArgumentException 이름이 비어있거나 중복된 경우
     */
    @Transactional
    public Industry createIndustry(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Industry 이름이 필요합니다.");
        }

        // 중복 체크
        if (industryRepository.existsByName(name.trim())) {
            throw new IllegalArgumentException("이미 존재하는 Industry입니다.");
        }

        Industry industry = Industry.builder()
                .name(name.trim())
                .build();

        Industry savedIndustry = industryRepository.save(industry);

        log.info("Industry 생성 완료 - ID: {}, 이름: {}", savedIndustry.getId(), savedIndustry.getName());

        return savedIndustry;
    }

    /**
     * Industry 삭제
     * - Industry를 사용하는 모든 기업에서 cascade로 자동 제거
     *
     * @param id 삭제할 Industry ID
     * @return 삭제 결과
     * @throws IllegalArgumentException Industry가 존재하지 않는 경우
     */
    @Transactional
    public IndustryDeleteResult deleteIndustry(Integer id) {
        // Industry 존재 확인
        Industry industry = industryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 Industry입니다. ID: " + id));

        // Industry를 사용하는 기업 수 확인
        int corporationCount = industry.getCorporationIndustries().size();

        log.info("Industry 삭제 시작 - ID: {}, 이름: {}, 연결된 기업 수: {}",
                id, industry.getName(), corporationCount);

        String industryName = industry.getName();

        // Industry 삭제 (cascade로 CorporationIndustry도 자동 삭제됨)
        industryRepository.delete(industry);

        log.info("Industry 삭제 완료 - ID: {}, 이름: {}", id, industryName);

        return IndustryDeleteResult.builder()
                .industryName(industryName)
                .affectedCorporationCount(corporationCount)
                .build();
    }

    /**
     * Corporation의 Industry 매핑 업데이트
     *
     * @param corporationId Corporation ID
     * @param industryIds 새로운 Industry ID 목록
     * @throws IllegalArgumentException Corporation이 존재하지 않는 경우
     */
    @Transactional
    public void updateCorporationIndustries(Long corporationId, List<Integer> industryIds) {
        if (industryIds == null) {
            industryIds = new ArrayList<>();
        }

        // 기존 Corporation 정보 조회
        CorporationResponseDto corporation = corporationService.getCorporationById(corporationId);

        // CorporationUpdateDto 생성 (기존 정보 유지하면서 industryIds만 업데이트)
        CorporationUpdateDto updateDto = new CorporationUpdateDto();
        updateDto.setName(corporation.getName());
        updateDto.setHomeLink(corporation.getHomeLink());
        updateDto.setBlogLink(corporation.getBlogLink());
        updateDto.setCrewLink(corporation.getCrewLink());
        updateDto.setLogoUrl(corporation.getLogoUrl());
        updateDto.setYoutubeUrl(corporation.getYoutubeUrl());
        updateDto.setBaseUrl(corporation.getBaseUrl());
        updateDto.setArticle(corporation.getArticle());
        updateDto.setTitle(corporation.getTitle());
        updateDto.setLink(corporation.getLink());
        updateDto.setThumbnail(corporation.getThumbnail());
        updateDto.setPublish(corporation.getPublish());
        updateDto.setPublishFormat(corporation.getPublishFormat());
        updateDto.setIndustryIds(industryIds);

        corporationService.updateCorporation(corporationId, updateDto);

        log.info("Corporation ID {}의 Industry 매핑 업데이트 완료 - Industry 수: {}",
                corporationId, industryIds.size());
    }
}
