package com.newcodes7.small_town.corporation.service;

import com.newcodes7.small_town.corporation.dto.CorporationCreateDto;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.dto.CorporationUpdateDto;
import com.newcodes7.small_town.corporation.entity.Corporation;
import com.newcodes7.small_town.corporation.entity.CorporationIndustry;
import com.newcodes7.small_town.corporation.entity.Industry;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.corporation.repository.IndustryRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorporationService {
    
    private final CorporationRepository corporationRepository;
    private final IndustryRepository industryRepository;
    
    public Page<CorporationResponseDto> getAllCorporations(Pageable pageable) {
        return corporationRepository.findAllActive(pageable)
                .map(CorporationResponseDto::from);
    }
    
    public Page<CorporationResponseDto> searchCorporations(String name, Pageable pageable) {
        return corporationRepository.findByNameContainingAndDeletedAtIsNull(name, pageable)
                .map(CorporationResponseDto::from);
    }
    
    public CorporationResponseDto getCorporationById(Long id) {
        Corporation corporation = corporationRepository.findActiveById(id)
                .orElseThrow(() -> new IllegalArgumentException("기업을 찾을 수 없습니다."));
        return CorporationResponseDto.from(corporation);
    }
    
    @Transactional
    public CorporationResponseDto createCorporation(CorporationCreateDto dto) {
        // 중복 검사
        if (corporationRepository.existsByNameAndDeletedAtIsNull(dto.getName())) {
            throw new IllegalArgumentException("이미 존재하는 기업명입니다.");
        }
        
        Corporation corporation = Corporation.builder()
                .name(dto.getName())
                .homeLink(dto.getHomeLink())
                .blogLink(dto.getBlogLink())
                .crewLink(dto.getCrewLink())
                .logoUrl(dto.getLogoUrl())
                .build();
        
        Corporation savedCorporation = corporationRepository.save(corporation);
        
        // 업종 관계 설정
        if (dto.getIndustryIds() != null && !dto.getIndustryIds().isEmpty()) {
            List<Industry> industries = industryRepository.findAllById(dto.getIndustryIds());
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
        Corporation corporation = corporationRepository.findActiveById(id)
                .orElseThrow(() -> new IllegalArgumentException("기업을 찾을 수 없습니다."));
        
        // 다른 기업이 같은 이름을 사용하는지 확인
        if (!corporation.getName().equals(dto.getName()) && 
            corporationRepository.existsByNameAndDeletedAtIsNull(dto.getName())) {
            throw new IllegalArgumentException("이미 존재하는 기업명입니다.");
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
            for (Industry industry : industries) {
                CorporationIndustry corporationIndustry = CorporationIndustry.builder()
                        .corporation(corporation)
                        .industry(industry)
                        .build();
                corporation.getCorporationIndustries().add(corporationIndustry);
            }
        }
        
        return CorporationResponseDto.from(corporation);
    }
    
    @Transactional
    public void deleteCorporation(Long id) {
        Corporation corporation = corporationRepository.findActiveById(id)
                .orElseThrow(() -> new IllegalArgumentException("기업을 찾을 수 없습니다."));
        corporation.softDelete();
    }
}