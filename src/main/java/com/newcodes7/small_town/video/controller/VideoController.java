package com.newcodes7.small_town.video.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.service.CorporationService;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.video.dto.VideoResponseDto;
import com.newcodes7.small_town.video.service.VideoService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Controller
@RequestMapping("/video")
@RequiredArgsConstructor
@Slf4j
public class VideoController {

    private final VideoService videoService;
    private final CorporationService corporationService;
    private final CategoryRepository categoryRepository;

    @GetMapping({"", "/"})
    public String videoHome(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "sort", defaultValue = "latest") String sort,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "regions", required = false) List<String> regions,
            @RequestParam(name = "view", defaultValue = "grouped") String view,
            @RequestParam(name = "category", required = false) List<String> category,
            Model model) {

        Page<VideoResponseDto> videos = videoService.getVideosWithFilters(
            keyword != null && !keyword.trim().isEmpty() ? keyword.trim() : null,
            regions == null ? null : regions.stream().sorted().toList(),
            page,
            size,
            sort,
            view,
            category == null ? null : category.stream().sorted().toList()
        );

        log.info("필터 조건: keyword='{}', regions={}, {}개의 영상 조회",
                 keyword, regions, videos.getTotalElements());

        // 회사 목록 가져오기 (YouTube 채널이 있는 회사만)
        Page<CorporationResponseDto> corporations = corporationService.getAllCorporations(PageRequest.of(0, 50));
        List<CorporationResponseDto> corporationsWithYouTube = corporations.getContent().stream()
            .filter(corp -> corp.getYoutubeUrl() != null && !corp.getYoutubeUrl().isEmpty())
            .limit(20)
            .toList();

        // 카테고리 목록 가져오기
        List<Category> categories = categoryRepository.findAll();

        log.info("YouTube 채널이 있는 회사 수: {}", corporationsWithYouTube.size());

        model.addAttribute("videos", videos);
        model.addAttribute("totalPages", videos.getTotalPages());
        model.addAttribute("totalElements", videos.getTotalElements());
        model.addAttribute("hasNext", videos.hasNext());
        model.addAttribute("hasPrevious", videos.hasPrevious());
        model.addAttribute("currentPage", page);
        model.addAttribute("currentSort", sort);
        model.addAttribute("keyword", keyword);
        model.addAttribute("selectedRegions", regions != null ? regions : new ArrayList<>());
        model.addAttribute("corporations", corporationsWithYouTube);
        model.addAttribute("isGrouped", view.equals("grouped"));
        model.addAttribute("categories", categories);

        return "video";
    }
}
