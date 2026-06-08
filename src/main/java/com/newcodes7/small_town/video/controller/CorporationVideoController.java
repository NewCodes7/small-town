package com.newcodes7.small_town.video.controller;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.newcodes7.small_town.corporation.dto.CorporationDetailDto;
import com.newcodes7.small_town.article.service.ArticleService;
import com.newcodes7.small_town.video.dto.VideoListResponseDto;
import com.newcodes7.small_town.video.service.VideoService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Controller
@RequiredArgsConstructor
@Slf4j
public class CorporationVideoController {

    private final VideoService videoService;
    private final ArticleService articleService;

    @GetMapping("/corporations/{corporationId}/video")
    public String corporationVideo(@PathVariable Long corporationId,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "10") int size,
                                  Model model) {
        CorporationDetailDto corporation = articleService.getCorporationDetail(corporationId);

        Page<VideoListResponseDto> videos = videoService.getVideosByCorporation(corporationId, page, size);

        model.addAttribute("corporation", corporation);
        model.addAttribute("videos", videos);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", videos.getTotalPages());
        model.addAttribute("totalElements", videos.getTotalElements());

        return "corporation-video";
    }
}
