package com.newcodes7.small_town.corporation.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.newcodes7.small_town.corporation.dto.CorporationCreateDto;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.dto.CorporationUpdateDto;
import com.newcodes7.small_town.corporation.repository.IndustryRepository;
import com.newcodes7.small_town.corporation.service.CorporationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {
    
    private final CorporationService corporationService;
    private final IndustryRepository industryRepository;
    
    // 기업 목록 페이지
    @GetMapping("/corporations")
    public String corporationList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            Model model) {
        
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<CorporationResponseDto> corporations;
        
        if (search != null && !search.trim().isEmpty()) {
            corporations = corporationService.searchCorporations(search, pageable);
            model.addAttribute("search", search);
        } else {
            corporations = corporationService.getAllCorporations(pageable);
        }
        
        model.addAttribute("corporations", corporations);
        return "admin/corporation/list";
    }
    
    // 기업 등록 폼 페이지
    @GetMapping("/corporations/new")
    public String corporationForm(Model model) {
        model.addAttribute("corporation", new CorporationCreateDto());
        model.addAttribute("industries", industryRepository.findAll());
        return "admin/corporation/form";
    }
    
    // 기업 등록 처리
    @PostMapping("/corporations")
    public String createCorporation(
            @Valid @ModelAttribute("corporation") CorporationCreateDto dto,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        
        if (bindingResult.hasErrors()) {
            model.addAttribute("industries", industryRepository.findAll());
            return "admin/corporation/form";
        }
        
        try {
            corporationService.createCorporation(dto);
            redirectAttributes.addFlashAttribute("successMessage", "기업이 성공적으로 등록되었습니다.");
            return "redirect:/admin/corporations";
        } catch (IllegalArgumentException e) {
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("industries", industryRepository.findAll());
            return "admin/corporation/form";
        }
    }
    
    // 기업 수정 폼 페이지
    @GetMapping("/corporations/{id}/edit")
    public String corporationEditForm(@PathVariable Long id, Model model) {
        try {
            CorporationResponseDto corporation = corporationService.getCorporationById(id);
            CorporationUpdateDto updateDto = new CorporationUpdateDto();
            updateDto.setName(corporation.getName());
            updateDto.setHomeLink(corporation.getHomeLink());
            updateDto.setBlogLink(corporation.getBlogLink());
            updateDto.setCrewLink(corporation.getCrewLink());
            updateDto.setLogoUrl(corporation.getLogoUrl());
            
            model.addAttribute("corporation", updateDto);
            model.addAttribute("corporationId", id);
            model.addAttribute("industries", industryRepository.findAll());
            return "admin/corporation/edit";
        } catch (IllegalArgumentException e) {
            return "redirect:/admin/corporations";
        }
    }
    
    // 기업 수정 처리
    @PostMapping("/corporations/{id}")
    public String updateCorporation(
            @PathVariable Long id,
            @Valid @ModelAttribute("corporation") CorporationUpdateDto dto,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        
        if (bindingResult.hasErrors()) {
            model.addAttribute("corporationId", id);
            model.addAttribute("industries", industryRepository.findAll());
            return "admin/corporation/edit";
        }
        
        try {
            corporationService.updateCorporation(id, dto);
            redirectAttributes.addFlashAttribute("successMessage", "기업 정보가 성공적으로 수정되었습니다.");
            return "redirect:/admin/corporations";
        } catch (IllegalArgumentException e) {
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("corporationId", id);
            model.addAttribute("industries", industryRepository.findAll());
            return "admin/corporation/edit";
        }
    }
    
    // 기업 삭제 처리
    @PostMapping("/corporations/{id}/delete")
    public String deleteCorporation(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            corporationService.deleteCorporation(id);
            redirectAttributes.addFlashAttribute("successMessage", "기업이 성공적으로 삭제되었습니다.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/corporations";
    }
}