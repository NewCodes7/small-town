package com.newcodes7.small_town.corporation.controller;

import com.newcodes7.small_town.corporation.dto.CorporationCreateDto;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.dto.CorporationUpdateDto;
import com.newcodes7.small_town.corporation.service.CorporationService;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.Map;

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
        try {
            CorporationResponseDto corporation = corporationService.getCorporationById(id);
            return ResponseEntity.ok(corporation);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
    
    // 기업 등록
    @PostMapping
    public ResponseEntity<?> createCorporation(@Valid @RequestBody CorporationCreateDto dto) {
        try {
            CorporationResponseDto corporation = corporationService.createCorporation(dto);
            return ResponseEntity.status(HttpStatus.CREATED).body(corporation);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }
    
    // 기업 수정
    @PutMapping("/{id}")
    public ResponseEntity<?> updateCorporation(
            @PathVariable Long id,
            @Valid @RequestBody CorporationUpdateDto dto) {
        try {
            CorporationResponseDto corporation = corporationService.updateCorporation(id, dto);
            return ResponseEntity.ok(corporation);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }
    
    // 기업 삭제
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteCorporation(@PathVariable Long id) {
        try {
            corporationService.deleteCorporation(id);
            return ResponseEntity.ok(Map.of("message", "기업이 성공적으로 삭제되었습니다."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}