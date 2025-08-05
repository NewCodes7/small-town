package com.newcodes7.small_town.corporation.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.service.CorporationService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/corporations")
@RequiredArgsConstructor
public class CorporationApiController {
    
    private final CorporationService corporationService;
    
    // 기업 목록 조회
    @GetMapping
    public ResponseEntity<Page<CorporationResponseDto>> getCorporations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search) {
        
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<CorporationResponseDto> corporations;
        
        if (search != null && !search.trim().isEmpty()) {
            corporations = corporationService.searchCorporations(search, pageable);
        } else {
            corporations = corporationService.getAllCorporations(pageable);
        }
        
        return ResponseEntity.ok(corporations);
    }
    
    // 기업 상세 조회
    @GetMapping("/{id}")
    public ResponseEntity<CorporationResponseDto> getCorporation(@PathVariable Long id) {
        CorporationResponseDto corporation = corporationService.getCorporationById(id);
        return ResponseEntity.ok(corporation);
    }
}