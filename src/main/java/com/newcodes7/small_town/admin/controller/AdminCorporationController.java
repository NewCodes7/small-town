package com.newcodes7.small_town.admin.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
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
import com.newcodes7.small_town.term.repository.ArticleTermRepository;
import com.newcodes7.small_town.like.repository.LikeLogRepository;
import com.newcodes7.small_town.like.repository.LikeRepository;
import com.newcodes7.small_town.view.repository.ViewLogRepository;
import com.newcodes7.small_town.crawler.repository.ArticleSummaryRepository;
import com.newcodes7.small_town.crawler.repository.ArticleTagRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;
import com.newcodes7.small_town.embedding.repository.ArticleChunkRepository;
import com.newcodes7.small_town.embedding.service.ChunkEmbeddingBatchService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.theme.repository.ThemeArticleRepository;

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
    private final CrawlerCorporationRepository crawlerCorporationRepository;
    private final CrawlerArticleRepository crawlerArticleRepository;

    // Article 삭제 시 연관 데이터 삭제를 위한 Repository들
    private final ArticleTermRepository articleTermRepository;
    private final ArticleChunkRepository articleChunkRepository;
    private final ChunkEmbeddingBatchService chunkEmbeddingBatchService;
    private final ArticleTagRepository articleTagRepository;
    private final ArticleSummaryRepository articleSummaryRepository;
    private final ThemeArticleRepository themeArticleRepository;
    private final LikeLogRepository likeLogRepository;
    private final LikeRepository likeRepository;
    private final ViewLogRepository viewLogRepository;

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
            updateDto.setBlogType(corporation.getBlogType());
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
            updateDto.setPublishType(corporation.getPublishType());
            updateDto.setInnerPublishSelector(corporation.getInnerPublishSelector());
            updateDto.setPaginationType(corporation.getPaginationType());
            updateDto.setPageUrlPattern(corporation.getPageUrlPattern());
            updateDto.setNextPageSelector(corporation.getNextPageSelector());
            updateDto.setMaxPages(corporation.getMaxPages());
            updateDto.setEffectiveLogoUrl(corporation.getEffectiveLogoUrl());

            // 업종 ID 리스트 설정 (Corporation 엔티티에서 직접 가져오기)
            Corporation corpEntity = crawlerCorporationRepository.findByIdAndNotDeleted(id);
            if (corpEntity != null && corpEntity.getCorporationIndustries() != null) {
                List<Integer> industryIds = corpEntity.getCorporationIndustries().stream()
                    .map(ci -> ci.getIndustry().getId())
                    .collect(java.util.stream.Collectors.toList());
                updateDto.setIndustryIds(industryIds);
            }

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

    /**
     * 기업의 전체 크롤링 상태 리셋 (lastFullCrawledAt을 null로 설정)
     */
    @PostMapping("/corporations/{id}/reset-full-crawl")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resetFullCrawlStatus(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            Corporation corporation = crawlerCorporationRepository.findByIdAndNotDeleted(id);
            if (corporation == null) {
                response.put("success", false);
                response.put("message", "기업을 찾을 수 없습니다.");
                return ResponseEntity.badRequest().body(response);
            }

            corporation.setLastFullCrawledAt(null);
            corporation.setLastFullCrawlStatus(null);
            crawlerCorporationRepository.save(corporation);

            response.put("success", true);
            response.put("message", String.format("'%s' 기업의 전체 크롤링 상태가 리셋되었습니다.", corporation.getName()));
            response.put("corporationName", corporation.getName());

            log.info("전체 크롤링 상태 리셋 완료 - 기업: {} (ID: {})", corporation.getName(), id);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("전체 크롤링 상태 리셋 실패 - ID: {}, 오류: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "리셋 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 기업의 모든 아티클 청크/벡터 임베딩 재생성
     * 기존 chunk, chunk_content, chunk_vector를 삭제하고 article.content 기반으로 재생성
     *
     * Example: POST /admin/corporations/3/regenerate-embeddings
     */
    @PostMapping("/corporations/{id}/regenerate-embeddings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> regenerateCorporationEmbeddings(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            Corporation corporation = crawlerCorporationRepository.findByIdAndNotDeleted(id);
            if (corporation == null) {
                response.put("success", false);
                response.put("message", "기업을 찾을 수 없습니다: " + id);
                return ResponseEntity.badRequest().body(response);
            }

            List<Long> targetIds = articleChunkRepository
                    .findArticleIdsWithEmbeddingByCorporationIdOrderByIdDesc(id);

            if (targetIds.isEmpty()) {
                response.put("success", true);
                response.put("corporationId", id);
                response.put("corporationName", corporation.getName());
                response.put("totalArticles", 0);
                response.put("message", "재생성할 아티클이 없습니다.");
                return ResponseEntity.ok(response);
            }

            log.info("기업 {} ({}) 임베딩 재생성 시작 - {}개 아티클", corporation.getName(), id, targetIds.size());

            int batchSize = chunkEmbeddingBatchService.getBatchSize();
            int totalSuccess = 0;
            int totalFailure = 0;
            int totalChunks = 0;
            int batchNumber = 0;

            for (int i = 0; i < targetIds.size(); i += batchSize) {
                List<Long> batchIds = targetIds.subList(i, Math.min(i + batchSize, targetIds.size()));
                batchNumber++;

                try {
                    Map<String, Object> batchResult = chunkEmbeddingBatchService.processRegenerateBatch(batchIds);
                    totalSuccess += (int) batchResult.get("successCount");
                    totalFailure += (int) batchResult.get("failureCount");
                    totalChunks += (int) batchResult.get("totalChunks");
                } catch (Exception ex) {
                    log.error("기업 {} 재생성 배치 {} 실패: {}", id, batchNumber, ex.getMessage());
                    totalFailure += batchIds.size();
                }
            }

            log.info("기업 {} 임베딩 재생성 완료 - 성공: {}/{}, 청크: {}",
                    corporation.getName(), totalSuccess, targetIds.size(), totalChunks);

            response.put("success", true);
            response.put("corporationId", id);
            response.put("corporationName", corporation.getName());
            response.put("totalArticles", targetIds.size());
            response.put("totalBatches", batchNumber);
            response.put("successArticles", totalSuccess);
            response.put("failureArticles", totalFailure);
            response.put("totalChunksGenerated", totalChunks);
            response.put("message", String.format("임베딩 재생성 완료: %d/%d Articles, %d 청크",
                    totalSuccess, targetIds.size(), totalChunks));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("기업 {} 임베딩 재생성 중 오류 발생", id, e);
            response.put("success", false);
            response.put("message", "재생성 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
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

    // ========== Medium 중복 글 관리 ==========

    /**
     * Medium 기업들의 중복 글 삭제 API
     * 제목 또는 링크가 같은 글 중 하나를 삭제합니다.
     * 삭제 우선순위: 썸네일 없는 글 > 조회수 적은 글
     */
    @PostMapping("/corporations/medium/remove-duplicates")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> removeMediumDuplicates() {
        Map<String, Object> response = new HashMap<>();

        try {
            List<Corporation> mediumCorporations = crawlerCorporationRepository.findMediumCorporations();

            if (mediumCorporations.isEmpty()) {
                response.put("success", true);
                response.put("message", "Medium 기반 기업이 없습니다.");
                response.put("deletedCount", 0);
                return ResponseEntity.ok(response);
            }

            int totalDeletedCount = 0;
            List<Map<String, Object>> deletedArticles = new ArrayList<>();

            for (Corporation corporation : mediumCorporations) {
                List<Article> duplicates = crawlerArticleRepository.findDuplicateArticlesByCorporation(corporation.getId());

                if (duplicates.isEmpty()) {
                    continue;
                }

                // 제목별, 링크별로 그룹화하여 중복 쌍 찾기
                Map<String, List<Article>> titleGroups = new HashMap<>();
                Map<String, List<Article>> linkGroups = new HashMap<>();

                for (Article article : duplicates) {
                    // 제목으로 그룹화
                    titleGroups.computeIfAbsent(article.getTitle(), k -> new ArrayList<>()).add(article);
                    // 링크로 그룹화
                    linkGroups.computeIfAbsent(article.getLink(), k -> new ArrayList<>()).add(article);
                }

                // 이미 삭제된 글 ID 추적
                Set<Long> deletedIds = new HashSet<>();

                // 제목 중복 처리
                for (List<Article> group : titleGroups.values()) {
                    if (group.size() > 1) {
                        totalDeletedCount += processAndDeleteDuplicates(group, deletedIds, deletedArticles, "title");
                    }
                }

                // 링크 중복 처리
                for (List<Article> group : linkGroups.values()) {
                    if (group.size() > 1) {
                        totalDeletedCount += processAndDeleteDuplicates(group, deletedIds, deletedArticles, "link");
                    }
                }
            }

            response.put("success", true);
            response.put("message", String.format("Medium 기업들의 중복 글 %d개가 삭제되었습니다.", totalDeletedCount));
            response.put("deletedCount", totalDeletedCount);
            response.put("deletedArticles", deletedArticles);
            response.put("corporationsChecked", mediumCorporations.size());

            log.info("Medium 중복 글 삭제 완료 - 검사 기업: {}개, 삭제 글: {}개",
                    mediumCorporations.size(), totalDeletedCount);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Medium 중복 글 삭제 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "중복 글 삭제 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 중복 글 그룹에서 삭제할 글을 선택하여 soft delete
     * 삭제 우선순위: 썸네일 없는 글 > 조회수 적은 글
     */
    private int processAndDeleteDuplicates(List<Article> group, Set<Long> deletedIds,
                                            List<Map<String, Object>> deletedArticles, String duplicateType) {
        int deletedCount = 0;

        // 이미 삭제된 글 제외
        List<Article> activeArticles = group.stream()
                .filter(a -> !deletedIds.contains(a.getId()))
                .toList();

        if (activeArticles.size() <= 1) {
            return 0;
        }

        // 정렬: 썸네일 있는 것 먼저, 조회수 높은 것 먼저 (유지할 글이 앞으로)
        List<Article> sorted = new ArrayList<>(activeArticles);
        sorted.sort((a, b) -> {
            // 썸네일 있는 것 우선 (유지)
            boolean aHasThumbnail = a.getThumbnailImage() != null && !a.getThumbnailImage().isEmpty();
            boolean bHasThumbnail = b.getThumbnailImage() != null && !b.getThumbnailImage().isEmpty();
            if (aHasThumbnail != bHasThumbnail) {
                return aHasThumbnail ? -1 : 1;
            }
            // 조회수 높은 것 우선 (유지)
            return Integer.compare(b.getViewCount(), a.getViewCount());
        });

        // 첫 번째(유지할 글)를 제외하고 나머지 삭제
        for (int i = 1; i < sorted.size(); i++) {
            Article toDelete = sorted.get(i);
            Long articleId = toDelete.getId();

            // 연관 데이터 먼저 삭제 (FK 제약 해결)
            articleTermRepository.deleteByArticleId(articleId);
            articleChunkRepository.deleteByArticleId(articleId);
            articleTagRepository.deleteByArticleId(articleId);
            articleSummaryRepository.deleteByArticleId(articleId);
            themeArticleRepository.deleteByArticleId(articleId);
            likeLogRepository.deleteByArticleId(articleId);
            likeRepository.deleteByArticleId(articleId);
            viewLogRepository.deleteByArticleId(articleId);

            // Hard delete
            crawlerArticleRepository.delete(toDelete);
            deletedIds.add(articleId);

            // 삭제 정보 기록
            Map<String, Object> info = new HashMap<>();
            info.put("id", toDelete.getId());
            info.put("title", toDelete.getTitle());
            info.put("link", toDelete.getLink());
            info.put("corporationName", toDelete.getCorporation().getName());
            info.put("duplicateType", duplicateType);
            info.put("hasThumbnail", toDelete.getThumbnailImage() != null && !toDelete.getThumbnailImage().isEmpty());
            info.put("viewCount", toDelete.getViewCount());
            deletedArticles.add(info);

            deletedCount++;

            log.debug("중복 글 삭제 - ID: {}, 제목: {}, 중복 유형: {}",
                    toDelete.getId(), toDelete.getTitle(), duplicateType);
        }

        return deletedCount;
    }
}
