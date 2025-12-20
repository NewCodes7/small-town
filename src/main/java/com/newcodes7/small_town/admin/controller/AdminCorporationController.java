package com.newcodes7.small_town.admin.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.newcodes7.small_town.admin.service.AdminIndustryService;
import com.newcodes7.small_town.corporation.dto.CorporationCreateDto;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.dto.CorporationUpdateDto;
import com.newcodes7.small_town.corporation.exception.CorporationException;
import com.newcodes7.small_town.corporation.repository.IndustryRepository;
import com.newcodes7.small_town.corporation.service.CorporationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Corporation 및 Industry 관리 Controller
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminCorporationController {

    private final CorporationService corporationService;
    private final IndustryRepository industryRepository;
    private final AdminIndustryService adminIndustryService;

    // ========== Corporation 관리 ==========

    /**
     * 기업 목록 페이지
     */
    @GetMapping("/corporations")
    public String corporationList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            Model model) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        // 통합 필터링 메서드 사용
        Page<CorporationResponseDto> corporations = corporationService.getCorporationsWithFilters(search, null, null, pageable);

        model.addAttribute("corporations", corporations);
        if (search != null && !search.trim().isEmpty()) {
            model.addAttribute("search", search);
        }
        return "admin/corporation/list";
    }

    /**
     * 기업 등록 폼 페이지
     */
    @GetMapping("/corporations/new")
    public String corporationForm(Model model) {
        model.addAttribute("corporation", new CorporationCreateDto());
        model.addAttribute("industries", industryRepository.findAll());
        return "admin/corporation/form";
    }

    /**
     * 기업 등록 처리
     */
    @PostMapping("/corporations")
    public String createCorporation(
            @Valid @ModelAttribute("corporation") CorporationCreateDto dto,
            @RequestParam(value = "logoFile", required = false) MultipartFile logoFile,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            model.addAttribute("industries", industryRepository.findAll());
            return "admin/corporation/form";
        }

        try {
            if (logoFile != null && !logoFile.isEmpty()) {
                corporationService.createCorporationWithLogo(dto, logoFile);
            } else {
                corporationService.createCorporation(dto);
            }
            redirectAttributes.addFlashAttribute("successMessage", "기업이 성공적으로 등록되었습니다.");
            return "redirect:/admin/corporations";
        } catch (Exception e) {
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("industries", industryRepository.findAll());
            return "admin/corporation/form";
        }
    }

    /**
     * 기업 수정 폼 페이지
     */
    @GetMapping("/corporations/{id}/edit")
    public String corporationEditForm(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String search,
            Model model) {
        try {
            CorporationResponseDto corporation = corporationService.getCorporationById(id);
            CorporationUpdateDto updateDto = new CorporationUpdateDto();
            updateDto.setName(corporation.getName());
            updateDto.setAlternateName(corporation.getAlternateName());
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
            updateDto.setPaginationType(corporation.getPaginationType());
            updateDto.setPageUrlPattern(corporation.getPageUrlPattern());
            updateDto.setNextPageSelector(corporation.getNextPageSelector());
            updateDto.setMaxPages(corporation.getMaxPages());
            updateDto.setEffectiveLogoUrl(corporation.getEffectiveLogoUrl());

            // 업종 ID 리스트 설정 (업종 이름 -> 업종 ID로 변환)
            List<Integer> industryIds = industryRepository.findAll().stream()
                .filter(industry -> corporation.getIndustries().contains(industry.getName()))
                .map(industry -> industry.getId())
                .collect(java.util.stream.Collectors.toList());
            updateDto.setIndustryIds(industryIds);

            model.addAttribute("corporation", updateDto);
            model.addAttribute("corporationId", id);
            model.addAttribute("industries", industryRepository.findAll());
            model.addAttribute("page", page);
            model.addAttribute("search", search);
            return "admin/corporation/edit";
        } catch (CorporationException e) {
            return "redirect:/admin/corporations";
        }
    }

    /**
     * 기업 수정 처리
     */
    @PostMapping("/corporations/{id}")
    public String updateCorporation(
            @PathVariable Long id,
            @Valid @ModelAttribute("corporation") CorporationUpdateDto dto,
            @RequestParam(value = "logoFile", required = false) MultipartFile logoFile,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String search,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            model.addAttribute("corporationId", id);
            model.addAttribute("industries", industryRepository.findAll());
            model.addAttribute("page", page);
            model.addAttribute("search", search);
            return "admin/corporation/edit";
        }

        try {
            if (logoFile != null && !logoFile.isEmpty()) {
                corporationService.updateCorporationWithLogo(id, dto, logoFile);
            } else {
                corporationService.updateCorporation(id, dto);
            }
            redirectAttributes.addFlashAttribute("successMessage", "기업 정보가 성공적으로 수정되었습니다.");

            // 원래 페이지와 검색어로 리다이렉트
            String redirectUrl = "redirect:/admin/corporations?page=" + page;
            if (search != null && !search.trim().isEmpty()) {
                redirectUrl += "&search=" + search;
            }
            return redirectUrl;
        } catch (Exception e) {
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("corporationId", id);
            model.addAttribute("industries", industryRepository.findAll());
            model.addAttribute("page", page);
            model.addAttribute("search", search);
            return "admin/corporation/edit";
        }
    }

    /**
     * 기업 삭제 처리
     */
    @PostMapping("/corporations/{id}/delete")
    public String deleteCorporation(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            corporationService.deleteCorporation(id);
            redirectAttributes.addFlashAttribute("successMessage", "기업이 성공적으로 삭제되었습니다.");
        } catch (CorporationException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/corporations";
    }

    /**
     * 기업의 모든 글 삭제 처리
     */
    @PostMapping("/corporations/{id}/delete-all-articles")
    public String deleteAllArticles(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            corporationService.deleteAllArticles(id);
            redirectAttributes.addFlashAttribute("successMessage", "기업의 모든 글이 성공적으로 삭제되었습니다.");
        } catch (CorporationException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/corporations/" + id + "/edit";
    }

    // ========== Industry 관리 ==========

    /**
     * Industry 목록 조회 API
     */
    @GetMapping("/industries")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getIndustries() {
        Map<String, Object> response = new HashMap<>();

        try {
            response.put("success", true);
            response.put("industries", adminIndustryService.getAllIndustries());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Industry 목록 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Industry 생성 API
     */
    @PostMapping("/industries")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createIndustry(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            String name = request.get("name");
            com.newcodes7.small_town.corporation.entity.Industry savedIndustry =
                    adminIndustryService.createIndustry(name);

            response.put("success", true);
            response.put("message", "Industry가 성공적으로 생성되었습니다.");
            response.put("industry", savedIndustry);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("Industry 생성 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Industry 생성 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Industry 삭제 API
     * Industry를 삭제하면 cascade로 해당 Industry를 사용하는 모든 기업에서 자동으로 제거됩니다.
     */
    @DeleteMapping("/industries/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteIndustry(@PathVariable Integer id) {
        Map<String, Object> response = new HashMap<>();

        try {
            // Service 계층에서 비즈니스 로직 처리
            AdminIndustryService.IndustryDeleteResult result =
                    adminIndustryService.deleteIndustry(id);

            response.put("success", true);
            response.put("message", String.format("Industry '%s'가 성공적으로 삭제되었습니다. (%d개 기업에서 제거됨)",
                    result.getIndustryName(),
                    result.getAffectedCorporationCount()));
            response.put("deletedIndustryName", result.getIndustryName());
            response.put("affectedCorporationCount", result.getAffectedCorporationCount());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Industry 삭제 실패 - ID: {}, 사유: {}", id, e.getMessage());
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("Industry 삭제 중 오류 발생 - ID: {}, 오류: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Industry 삭제 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Corporation의 Industry 수정 API
     */
    @PutMapping("/corporations/{corporationId}/industries")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateCorporationIndustries(
            @PathVariable Long corporationId,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            @SuppressWarnings("unchecked")
            List<Integer> industryIds = (List<Integer>) request.get("industryIds");

            // Service 계층에서 비즈니스 로직 처리
            adminIndustryService.updateCorporationIndustries(corporationId, industryIds);

            response.put("success", true);
            response.put("message", "Industry가 성공적으로 수정되었습니다.");
            response.put("corporationId", corporationId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Corporation Industry 수정 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Industry 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
