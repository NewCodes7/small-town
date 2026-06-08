package com.newcodes7.small_town.admin.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.term.repository.ArticleTermRepository;
import com.newcodes7.small_town.term.repository.TermRepository;
import com.newcodes7.small_town.term.repository.UserDictionaryRepository;
import com.newcodes7.small_town.term.service.ArticleTermService;
import com.newcodes7.small_town.crawler.integration.translation.TranslationService;
import com.newcodes7.small_town.term.service.TermSynonymService;
import com.newcodes7.small_town.term.service.TechTermService;
import com.newcodes7.small_town.term.service.StackExchangeApiService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.video.repository.VideoRepository;
import com.newcodes7.small_town.video.service.VideoTermService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Term, UserDictionary, TermSynonym 관리 Controller
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminTermController {

    private final ArticleRepository articleRepository;
    private final ArticleTermRepository articleTermRepository;
    private final ArticleTermService articleTermService;
    private final VideoRepository videoRepository;
    private final VideoTermService videoTermService;
    private final UserDictionaryRepository userDictionaryRepository;
    private final TermSynonymService termSynonymService;
    private final TermRepository termRepository;
    private final TranslationService translationService;
    private final TechTermService techTermService;
    private final StackExchangeApiService stackExchangeApiService;

    // ========== Article Term 관리 ==========

    /**
     * Article Term 추출 및 저장 API
     * 모든 article의 title과 translatedTitle에서 형태소를 분석하여 term으로 저장
     */
    @GetMapping("/articles/extract-terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> extractArticleTerms() {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Article term 추출 요청 시작");

            // 비동기로 실행하여 오래 걸리는 작업이 UI를 블로킹하지 않도록 함
            new Thread(() -> {
                try {
                    ArticleTermService.ArticleTermExtractionResult result =
                            articleTermService.extractAndSaveAllArticleTerms();

                    log.info("Article term 추출 완료: 처리={}, 건너뜀={}, 실패={}, term={}, 소요시간={}ms",
                            result.getProcessedArticles(),
                            result.getSkippedArticles(),
                            result.getFailedArticles(),
                            result.getTotalTerms(),
                            result.getProcessingTimeMs());

                } catch (Exception e) {
                    log.error("Article term 추출 배치 작업 중 오류 발생", e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "Article term 추출 작업이 시작되었습니다. 이미 term이 있는 article은 건너뜁니다. 로그를 확인해주세요.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article term 추출 작업 시작 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 추출 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Article term 강제 재분석 (배치 작업)
     * 모든 article에 대해 term을 강제로 다시 추출 (기존 term이 있어도 재분석)
     *
     * @param maxArticleId 이 ID 이하의 article만 재분석 (선택사항, 미지정 시 전체)
     */
    @GetMapping("/articles/reextract-all-terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reextractAllArticleTerms(
            @RequestParam(required = false) Long maxArticleId) {
        Map<String, Object> response = new HashMap<>();

        try {
            String rangeInfo = maxArticleId != null ? " (ID <= " + maxArticleId + ")" : " (전체)";
            log.info("Article term 강제 재분석 요청 시작{}", rangeInfo);

            // 비동기로 실행
            new Thread(() -> {
                try {
                    ArticleTermService.ArticleTermExtractionResult result =
                            articleTermService.extractAndSaveAllArticleTerms(true, maxArticleId);

                    log.info("Article term 강제 재분석 완료: 처리={}, 건너뜀={}, 실패={}, term={}, 소요시간={}ms",
                            result.getProcessedArticles(),
                            result.getSkippedArticles(),
                            result.getFailedArticles(),
                            result.getTotalTerms(),
                            result.getProcessingTimeMs());

                } catch (Exception e) {
                    log.error("Article term 강제 재분석 배치 작업 중 오류 발생", e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "Article term 강제 재분석 작업이 시작되었습니다." + rangeInfo + " 로그를 확인해주세요.");
            if (maxArticleId != null) {
                response.put("maxArticleId", maxArticleId);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article term 강제 재분석 작업 시작 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 강제 재분석 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 Article의 Term 조회 API
     */
    @GetMapping("/articles/{articleId}/terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getArticleTerms(@PathVariable Long articleId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Article> articleOpt = articleRepository.findById(articleId);
            if (articleOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Article을 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            List<com.newcodes7.small_town.global.entity.ArticleTerm> terms =
                    articleTermService.getArticleTerms(articleId);

            // DTO로 변환
            List<Map<String, Object>> termDataList = new ArrayList<>();
            for (com.newcodes7.small_town.global.entity.ArticleTerm articleTerm : terms) {
                Map<String, Object> termData = new HashMap<>();
                termData.put("id", articleTerm.getId());
                termData.put("termId", articleTerm.getTerm().getId());
                termData.put("term", articleTerm.getTerm().getTerm());
                termData.put("termType", articleTerm.getTerm().getTermType());
                termData.put("frequency", articleTerm.getFrequency());
                termData.put("score", articleTerm.getScore());
                termData.put("createdAt", articleTerm.getCreatedAt());
                termDataList.add(termData);
            }

            response.put("success", true);
            response.put("articleId", articleId);
            response.put("terms", termDataList);
            response.put("totalTerms", termDataList.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article term 조회 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 여러 Article의 Term을 한 번에 조회하는 Bulk API
     * @param articleIds 쉼표로 구분된 Article ID 목록 (예: "1,2,3,4,5")
     */
    @GetMapping("/articles/terms/bulk")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getBulkArticleTerms(
            @RequestParam("articleIds") String articleIds) {

        Map<String, Object> response = new HashMap<>();

        try {
            // 쉼표로 구분된 ID 파싱
            String[] idStrings = articleIds.split(",");
            List<Long> articleIdList = new ArrayList<>();
            for (String idStr : idStrings) {
                try {
                    articleIdList.add(Long.parseLong(idStr.trim()));
                } catch (NumberFormatException e) {
                    log.warn("유효하지 않은 article ID: {}", idStr);
                }
            }

            if (articleIdList.isEmpty()) {
                response.put("success", false);
                response.put("message", "유효한 Article ID가 없습니다.");
                return ResponseEntity.badRequest().body(response);
            }

            // 모든 ArticleTerm을 한 번의 쿼리로 조회
            List<com.newcodes7.small_town.global.entity.ArticleTerm> allTerms =
                    articleTermRepository.findByArticleIdInOrderByScoreDesc(articleIdList);

            // Article ID별로 그룹화
            Map<Long, List<Map<String, Object>>> termsByArticle = new HashMap<>();
            for (com.newcodes7.small_town.global.entity.ArticleTerm articleTerm : allTerms) {
                Long articleId = articleTerm.getArticle().getId();

                termsByArticle.putIfAbsent(articleId, new ArrayList<>());

                Map<String, Object> termData = new HashMap<>();
                termData.put("id", articleTerm.getId());
                termData.put("termId", articleTerm.getTerm().getId());
                termData.put("term", articleTerm.getTerm().getTerm());
                termData.put("termType", articleTerm.getTerm().getTermType());
                termData.put("frequency", articleTerm.getFrequency());
                termData.put("score", articleTerm.getScore());

                termsByArticle.get(articleId).add(termData);
            }

            response.put("success", true);
            response.put("termsByArticle", termsByArticle);
            response.put("totalArticles", articleIdList.size());
            response.put("articlesWithTerms", termsByArticle.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Bulk article terms 조회 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "Bulk terms 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Article에 Term 추가 API
     */
    @PostMapping("/articles/{articleId}/terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addArticleTerm(
            @PathVariable Long articleId,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            String termString = (String) request.get("term");
            double score = request.get("score") != null ? ((Number) request.get("score")).doubleValue() : 0.5;
            int frequency = request.get("frequency") != null ? ((Number) request.get("frequency")).intValue() : 1;

            if (termString == null || termString.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "Term 문자열이 필요합니다.");
                return ResponseEntity.badRequest().body(response);
            }

            com.newcodes7.small_town.global.entity.ArticleTerm articleTerm =
                    articleTermService.addArticleTermForAdmin(articleId, termString, score, frequency);

            response.put("success", true);
            response.put("message", "Term이 성공적으로 추가되었습니다.");
            response.put("articleTermId", articleTerm.getId());
            response.put("term", articleTerm.getTerm().getTerm());
            response.put("score", score);

            log.info("Article ID {}에 Term '{}' 추가 완료 (score: {})", articleId, articleTerm.getTerm().getTerm(), score);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("ArticleTerm 추가 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 추가 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * ArticleTerm 수정 API (score, frequency 수정)
     */
    @PutMapping("/articles/{articleId}/terms/{articleTermId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateArticleTerm(
            @PathVariable Long articleId,
            @PathVariable Long articleTermId,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            Double newScore = request.containsKey("score") ? ((Number) request.get("score")).doubleValue() : null;
            Integer newFrequency = request.containsKey("frequency") ? ((Number) request.get("frequency")).intValue() : null;

            com.newcodes7.small_town.global.entity.ArticleTerm articleTerm =
                    articleTermService.updateArticleTermForAdmin(articleTermId, articleId, newScore, newFrequency);

            response.put("success", true);
            response.put("message", "ArticleTerm이 성공적으로 수정되었습니다.");
            response.put("articleTermId", articleTerm.getId());
            response.put("score", articleTerm.getScore());
            response.put("frequency", articleTerm.getFrequency());

            log.info("ArticleTerm ID {} 수정 완료 (score: {}, frequency: {})",
                    articleTermId, articleTerm.getScore(), articleTerm.getFrequency());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("ArticleTerm 수정 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "ArticleTerm 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * ArticleTerm 삭제 API
     */
    @DeleteMapping("/articles/{articleId}/terms/{articleTermId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteArticleTerm(
            @PathVariable Long articleId,
            @PathVariable Long articleTermId) {

        Map<String, Object> response = new HashMap<>();

        try {
            String termName = articleTermService.deleteArticleTermForAdmin(articleTermId, articleId);

            response.put("success", true);
            response.put("message", String.format("Term '%s'가 성공적으로 삭제되었습니다.", termName));
            response.put("deletedTerm", termName);

            log.info("ArticleTerm ID {} 삭제 완료 (term: {})", articleTermId, termName);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("ArticleTerm 삭제 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "ArticleTerm 삭제 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 Article의 Term 재분석 API
     */
    @PostMapping("/articles/{articleId}/reanalyze-terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reanalyzeArticleTerms(@PathVariable Long articleId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Article> articleOpt = articleRepository.findById(articleId);
            if (articleOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Article을 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            Article article = articleOpt.get();

            // Term 재분석 및 저장
            int termCount = articleTermService.extractAndSaveTermsForArticle(article);

            response.put("success", true);
            response.put("message", String.format("Term 재분석이 완료되었습니다. (%d개 추출)", termCount));
            response.put("articleId", articleId);
            response.put("termCount", termCount);

            log.info("Article ID {} term 재분석 완료: {} terms", articleId, termCount);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article ID {} term 재분석 중 오류 발생: {}", articleId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 재분석 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ========== General Term 관리 ==========

    /**
     * Term 삭제 및 불용어 등록 API
     */
    @DeleteMapping("/terms/{termId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteTerm(
            @PathVariable Long termId,
            @RequestBody(required = false) Map<String, String> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            String reason = request != null ? request.get("reason") : null;

            int deletedCount = articleTermService.deleteTermAndAddToStopwords(termId, reason);

            response.put("success", true);
            response.put("message", String.format("Term이 삭제되고 불용어로 등록되었습니다. (ArticleTerm + VideoTerm 총 %d개 삭제)", deletedCount));
            response.put("deletedTermCount", deletedCount);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("Term 삭제 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 삭제 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Term 검색 API (유의어 관리 UI용)
     * 최적화: findAll() 후 메모리 필터링 대신 DB에서 LIKE 검색
     */
    @GetMapping("/terms/search")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> searchTerms(@RequestParam String q) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 검색어가 너무 짧으면 빈 결과 반환 (DB 부하 방지)
            if (q == null || q.trim().length() < 2) {
                response.put("success", true);
                response.put("terms", new ArrayList<>());
                return ResponseEntity.ok(response);
            }

            // DB에서 직접 LIKE 검색 (최대 20개)
            List<com.newcodes7.small_town.global.entity.Term> terms =
                termRepository.searchByTermContaining(q.trim(), 20);

            // DTO로 변환
            List<Map<String, Object>> termDataList = new ArrayList<>();
            for (com.newcodes7.small_town.global.entity.Term term : terms) {
                Map<String, Object> termData = new HashMap<>();
                termData.put("id", term.getId());
                termData.put("term", term.getTerm());
                termData.put("termType", term.getTermType());
                termDataList.add(termData);
            }

            response.put("success", true);
            response.put("terms", termDataList);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Term 검색 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 검색 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ========== User Dictionary 관리 ==========

    /**
     * 사용자 정의 단어 사전 목록 조회 API
     */
    @GetMapping("/user-dictionary")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getUserDictionary() {
        Map<String, Object> response = new HashMap<>();

        try {
            List<com.newcodes7.small_town.global.entity.UserDictionary> userDictionaries =
                    userDictionaryRepository.findAllOrderByCreatedAtDesc();

            // DTO로 변환
            List<Map<String, Object>> dictDataList = new ArrayList<>();
            for (com.newcodes7.small_town.global.entity.UserDictionary userDict : userDictionaries) {
                Map<String, Object> dictData = new HashMap<>();
                dictData.put("id", userDict.getId());
                dictData.put("word", userDict.getWord());
                dictData.put("posTag", userDict.getPosTag());
                dictData.put("reason", userDict.getReason());
                dictData.put("createdAt", userDict.getCreatedAt());
                dictDataList.add(dictData);
            }

            response.put("success", true);
            response.put("userDictionaries", dictDataList);
            response.put("totalCount", dictDataList.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("사용자 사전 조회 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "사용자 사전 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 사용자 정의 단어 추가 API
     */
    @PostMapping("/user-dictionary")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addUserDictionary(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            String word = request.get("word");
            String posTag = request.get("posTag");
            String reason = request.get("reason");

            if (word == null || word.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "단어는 필수 입력 항목입니다.");
                return ResponseEntity.badRequest().body(response);
            }

            // 기본 품사는 NNG (일반명사)
            if (posTag == null || posTag.trim().isEmpty()) {
                posTag = "NNG";
            }

            // 중복 체크
            if (userDictionaryRepository.existsByWord(word.trim())) {
                response.put("success", false);
                response.put("message", "이미 등록된 단어입니다.");
                return ResponseEntity.badRequest().body(response);
            }

            // 사용자 단어 저장
            com.newcodes7.small_town.global.entity.UserDictionary userDict =
                com.newcodes7.small_town.global.entity.UserDictionary.builder()
                    .word(word.trim())
                    .posTag(posTag.trim())
                    .reason(reason != null ? reason.trim() : null)
                    .build();

            com.newcodes7.small_town.global.entity.UserDictionary saved =
                    userDictionaryRepository.save(userDict);

            response.put("success", true);
            response.put("message", "사용자 단어가 등록되었습니다. 애플리케이션을 재시작하면 형태소 분석에 적용됩니다.");
            response.put("userDictionary", Map.of(
                "id", saved.getId(),
                "word", saved.getWord(),
                "posTag", saved.getPosTag(),
                "reason", saved.getReason() != null ? saved.getReason() : ""
            ));

            log.info("사용자 단어 등록: {} ({}), 사유: {}", word.trim(), posTag.trim(), reason);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("사용자 단어 등록 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "사용자 단어 등록 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 사용자 정의 단어 삭제 API
     */
    @DeleteMapping("/user-dictionary/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteUserDictionary(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            // UserDictionary 존재 확인
            com.newcodes7.small_town.global.entity.UserDictionary userDict =
                userDictionaryRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자 단어입니다. ID: " + id));

            String word = userDict.getWord();

            // UserDictionary 삭제
            userDictionaryRepository.delete(userDict);

            response.put("success", true);
            response.put("message", String.format("사용자 단어 '%s'가 삭제되었습니다. 애플리케이션을 재시작하면 형태소 분석에서 제외됩니다.", word));
            response.put("deletedWord", word);

            log.info("사용자 단어 삭제: {} (ID: {})", word, id);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("사용자 단어 삭제 실패 - 존재하지 않는 ID: {}", id);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("사용자 단어 삭제 중 오류 발생 - ID: {}, 오류: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "사용자 단어 삭제 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ========== Video Term 관리 ==========

    /**
     * Video Term 추출 및 저장 API
     */
    @GetMapping("/videos/extract-terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> extractVideoTerms() {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Video term 추출 요청 시작");

            // 비동기로 실행
            new Thread(() -> {
                try {
                    VideoTermService.VideoTermExtractionResult result =
                            videoTermService.extractAndSaveAllVideoTerms();

                    log.info("Video term 추출 완료: 처리={}, 건너뜀={}, 실패={}, term={}, 소요시간={}ms",
                            result.getProcessedVideos(),
                            result.getSkippedVideos(),
                            result.getFailedVideos(),
                            result.getTotalTerms(),
                            result.getProcessingTimeMs());

                } catch (Exception e) {
                    log.error("Video term 추출 배치 작업 중 오류 발생", e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "Video term 추출 작업이 시작되었습니다. 이미 term이 있는 video는 건너뜁니다. 로그를 확인해주세요.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Video term 추출 작업 시작 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 추출 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Video term 강제 재분석 (배치 작업)
     */
    @GetMapping("/videos/reextract-all-terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reextractAllVideoTerms() {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Video term 강제 재분석 요청 시작");

            // 비동기로 실행
            new Thread(() -> {
                try {
                    VideoTermService.VideoTermExtractionResult result =
                            videoTermService.extractAndSaveAllVideoTerms(true);

                    log.info("Video term 강제 재분석 완료: 처리={}, 건너뜀={}, 실패={}, term={}, 소요시간={}ms",
                            result.getProcessedVideos(),
                            result.getSkippedVideos(),
                            result.getFailedVideos(),
                            result.getTotalTerms(),
                            result.getProcessingTimeMs());

                } catch (Exception e) {
                    log.error("Video term 강제 재분석 배치 작업 중 오류 발생", e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "Video term 강제 재분석 작업이 시작되었습니다. 모든 video를 재분석합니다. 로그를 확인해주세요.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Video term 강제 재분석 작업 시작 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 강제 재분석 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 Video의 Term 조회 API
     */
    @GetMapping("/videos/{videoId}/terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getVideoTerms(@PathVariable Long videoId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Video> videoOpt = videoRepository.findById(videoId);
            if (videoOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Video를 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            List<com.newcodes7.small_town.global.entity.VideoTerm> terms =
                    videoTermService.getVideoTerms(videoId);

            // DTO로 변환
            List<Map<String, Object>> termDataList = new ArrayList<>();
            for (com.newcodes7.small_town.global.entity.VideoTerm videoTerm : terms) {
                Map<String, Object> termData = new HashMap<>();
                termData.put("id", videoTerm.getId());
                termData.put("term", videoTerm.getTerm().getTerm());
                termData.put("termType", videoTerm.getTerm().getTermType());
                termData.put("frequency", videoTerm.getFrequency());
                termData.put("createdAt", videoTerm.getCreatedAt());
                termDataList.add(termData);
            }

            response.put("success", true);
            response.put("videoId", videoId);
            response.put("terms", termDataList);
            response.put("totalTerms", termDataList.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Video term 조회 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 여러 Video의 Term을 한 번에 조회하는 Bulk API
     * @param videoIds 쉼표로 구분된 Video ID 목록 (예: "1,2,3,4,5")
     */
    @GetMapping("/videos/terms/bulk")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getBulkVideoTerms(
            @RequestParam("videoIds") String videoIds) {

        Map<String, Object> response = new HashMap<>();

        try {
            // 쉼표로 구분된 ID 파싱
            String[] idStrings = videoIds.split(",");
            List<Long> videoIdList = new ArrayList<>();
            for (String idStr : idStrings) {
                try {
                    videoIdList.add(Long.parseLong(idStr.trim()));
                } catch (NumberFormatException e) {
                    log.warn("유효하지 않은 video ID: {}", idStr);
                }
            }

            if (videoIdList.isEmpty()) {
                response.put("success", false);
                response.put("message", "유효한 Video ID가 없습니다.");
                return ResponseEntity.badRequest().body(response);
            }

            // VideoTermRepository에 bulk 조회 메서드가 있다고 가정
            List<com.newcodes7.small_town.global.entity.VideoTerm> allTerms =
                    videoTermService.getVideoTermsByVideoIds(videoIdList);

            // Video ID별로 그룹화
            Map<Long, List<Map<String, Object>>> termsByVideo = new HashMap<>();
            for (com.newcodes7.small_town.global.entity.VideoTerm videoTerm : allTerms) {
                Long videoId = videoTerm.getVideo().getId();

                termsByVideo.putIfAbsent(videoId, new ArrayList<>());

                Map<String, Object> termData = new HashMap<>();
                termData.put("id", videoTerm.getId());
                termData.put("term", videoTerm.getTerm().getTerm());
                termData.put("termType", videoTerm.getTerm().getTermType());
                termData.put("frequency", videoTerm.getFrequency());

                termsByVideo.get(videoId).add(termData);
            }

            response.put("success", true);
            response.put("termsByVideo", termsByVideo);
            response.put("totalVideos", videoIdList.size());
            response.put("videosWithTerms", termsByVideo.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Bulk video terms 조회 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "Bulk terms 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 Video의 Term 재분석 API
     */
    @PostMapping("/videos/{videoId}/reanalyze-terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reanalyzeVideoTerms(@PathVariable Long videoId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Video> videoOpt = videoRepository.findById(videoId);
            if (videoOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Video를 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            Video video = videoOpt.get();

            // Term 재분석 및 저장
            int termCount = videoTermService.extractAndSaveTermsForVideo(video);

            response.put("success", true);
            response.put("message", String.format("Term 재분석이 완료되었습니다. (%d개 추출)", termCount));
            response.put("videoId", videoId);
            response.put("termCount", termCount);

            log.info("Video ID {} term 재분석 완료: {} terms", videoId, termCount);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Video ID {} term 재분석 중 오류 발생: {}", videoId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 재분석 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ========== Term Synonym 관리 ==========`

    /**
     * 모든 유의어 관계 조회 API
     */
    @GetMapping("/term-synonyms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getAllSynonyms() {
        Map<String, Object> response = new HashMap<>();

        try {
            List<com.newcodes7.small_town.global.entity.TermSynonym> synonyms = termSynonymService.getAllSynonyms();

            // DTO로 변환
            List<Map<String, Object>> synonymDataList = new ArrayList<>();
            for (com.newcodes7.small_town.global.entity.TermSynonym synonym : synonyms) {
                Map<String, Object> synonymData = new HashMap<>();
                synonymData.put("id", synonym.getId());
                synonymData.put("term1Id", synonym.getTerm().getId());
                synonymData.put("term1", synonym.getTerm().getTerm());
                synonymData.put("term1Type", synonym.getTerm().getTermType());
                synonymData.put("term2Id", synonym.getSynonymTerm().getId());
                synonymData.put("term2", synonym.getSynonymTerm().getTerm());
                synonymData.put("term2Type", synonym.getSynonymTerm().getTermType());
                synonymData.put("createdAt", synonym.getCreatedAt());
                synonymDataList.add(synonymData);
            }

            response.put("success", true);
            response.put("synonyms", synonymDataList);
            response.put("totalCount", synonymDataList.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("유의어 목록 조회 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "유의어 목록 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 유의어 관계 추가 API (ID 기반)
     */
    @PostMapping("/term-synonyms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addSynonym(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            com.newcodes7.small_town.global.entity.TermSynonym synonym = null;

            // ID 기반 (termId1 + termId2)
            if (request.containsKey("termId1") && request.containsKey("termId2")) {
                Long termId1 = ((Number) request.get("termId1")).longValue();
                Long termId2 = ((Number) request.get("termId2")).longValue();

                synonym = termSynonymService.addSynonym(termId1, termId2);
            }
            // 문자열 기반 (termString1 + termString2)
            else if (request.containsKey("termString1") && request.containsKey("termString2")) {
                String termString1 = (String) request.get("termString1");
                String termString2 = (String) request.get("termString2");

                if (termString1 == null || termString1.trim().isEmpty() ||
                    termString2 == null || termString2.trim().isEmpty()) {
                    response.put("success", false);
                    response.put("message", "두 개의 term 문자열이 필요합니다.");
                    return ResponseEntity.badRequest().body(response);
                }

                synonym = termSynonymService.addSynonymByTermString(termString1.trim(), termString2.trim());
            }
            // 혼합 형태 (termId1 + termString2)
            else if (request.containsKey("termId1") && request.containsKey("termString2")) {
                Long termId1 = ((Number) request.get("termId1")).longValue();
                String termString2 = (String) request.get("termString2");

                if (termString2 == null || termString2.trim().isEmpty()) {
                    response.put("success", false);
                    response.put("message", "term 문자열이 필요합니다.");
                    return ResponseEntity.badRequest().body(response);
                }

                // termId1으로 term 조회
                com.newcodes7.small_town.global.entity.Term term1 = termRepository.findById(termId1)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 term입니다. ID: " + termId1));

                // termString2로 유의어 관계 생성
                synonym = termSynonymService.addSynonymByTermString(term1.getTerm(), termString2.trim());
            }
            // 혼합 형태 (termString1 + termId2)
            else if (request.containsKey("termString1") && request.containsKey("termId2")) {
                String termString1 = (String) request.get("termString1");
                Long termId2 = ((Number) request.get("termId2")).longValue();

                if (termString1 == null || termString1.trim().isEmpty()) {
                    response.put("success", false);
                    response.put("message", "term 문자열이 필요합니다.");
                    return ResponseEntity.badRequest().body(response);
                }

                // termId2로 term 조회
                com.newcodes7.small_town.global.entity.Term term2 = termRepository.findById(termId2)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 term입니다. ID: " + termId2));

                // termString1으로 유의어 관계 생성
                synonym = termSynonymService.addSynonymByTermString(termString1.trim(), term2.getTerm());
            }
            else {
                response.put("success", false);
                response.put("message", "termId1/termId2, termString1/termString2, 또는 혼합 형태가 필요합니다.");
                return ResponseEntity.badRequest().body(response);
            }

            if (synonym != null) {
                response.put("success", true);
                response.put("message", "유의어 관계가 성공적으로 추가되었습니다.");
                response.put("synonymId", synonym.getId());
                response.put("term1", synonym.getTerm().getTerm());
                response.put("term2", synonym.getSynonymTerm().getTerm());
            } else {
                response.put("success", false);
                response.put("message", "유의어 추가에 실패했습니다.");
                return ResponseEntity.badRequest().body(response);
            }

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("유의어 추가 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "유의어 추가 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 유의어 관계 수정 API (ID 기반, 문자열 기반, 혼합 형태 모두 지원)
     */
    @PutMapping("/term-synonyms/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateSynonym(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            com.newcodes7.small_town.global.entity.TermSynonym synonym = null;

            // ID 기반 (termId1 + termId2)
            if (request.containsKey("termId1") && request.containsKey("termId2")) {
                Long termId1 = ((Number) request.get("termId1")).longValue();
                Long termId2 = ((Number) request.get("termId2")).longValue();

                synonym = termSynonymService.updateSynonym(id, termId1, termId2);
            }
            // 문자열 기반 (termString1 + termString2)
            else if (request.containsKey("termString1") && request.containsKey("termString2")) {
                String termString1 = (String) request.get("termString1");
                String termString2 = (String) request.get("termString2");

                if (termString1 == null || termString1.trim().isEmpty() ||
                    termString2 == null || termString2.trim().isEmpty()) {
                    response.put("success", false);
                    response.put("message", "두 개의 term 문자열이 필요합니다.");
                    return ResponseEntity.badRequest().body(response);
                }

                synonym = termSynonymService.updateSynonymByTermString(id, termString1.trim(), termString2.trim());
            }
            // 혼합 형태 (termId1 + termString2)
            else if (request.containsKey("termId1") && request.containsKey("termString2")) {
                Long termId1 = ((Number) request.get("termId1")).longValue();
                String termString2 = (String) request.get("termString2");

                if (termString2 == null || termString2.trim().isEmpty()) {
                    response.put("success", false);
                    response.put("message", "term 문자열이 필요합니다.");
                    return ResponseEntity.badRequest().body(response);
                }

                synonym = termSynonymService.updateSynonymMixed(id, termId1, null, null, termString2.trim());
            }
            // 혼합 형태 (termString1 + termId2)
            else if (request.containsKey("termString1") && request.containsKey("termId2")) {
                String termString1 = (String) request.get("termString1");
                Long termId2 = ((Number) request.get("termId2")).longValue();

                if (termString1 == null || termString1.trim().isEmpty()) {
                    response.put("success", false);
                    response.put("message", "term 문자열이 필요합니다.");
                    return ResponseEntity.badRequest().body(response);
                }

                synonym = termSynonymService.updateSynonymMixed(id, null, termString1.trim(), termId2, null);
            }
            else {
                response.put("success", false);
                response.put("message", "termId1/termId2, termString1/termString2, 또는 혼합 형태가 필요합니다.");
                return ResponseEntity.badRequest().body(response);
            }

            if (synonym != null) {
                response.put("success", true);
                response.put("message", "유의어 관계가 성공적으로 수정되었습니다.");
                response.put("synonymId", synonym.getId());
                response.put("term1", synonym.getTerm().getTerm());
                response.put("term2", synonym.getSynonymTerm().getTerm());
            } else {
                response.put("success", false);
                response.put("message", "유의어 수정에 실패했습니다.");
                return ResponseEntity.badRequest().body(response);
            }

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("유의어 수정 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "유의어 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 유의어 관계 삭제 API
     */
    @DeleteMapping("/term-synonyms/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteSynonym(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            termSynonymService.deleteSynonym(id);

            response.put("success", true);
            response.put("message", "유의어 관계가 성공적으로 삭제되었습니다.");

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("유의어 삭제 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "유의어 삭제 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * DeepL을 통한 유의어 추천 API
     */
    @PostMapping("/term-synonyms/recommend")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> recommendSynonyms(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            String term = request.get("term");

            if (term == null || term.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "term이 필요합니다.");
                return ResponseEntity.badRequest().body(response);
            }

            log.info("DeepL 유의어 추천 요청: {}", term);

            List<String> recommendations = translationService.recommendSynonyms(term.trim());

            response.put("success", true);
            response.put("term", term);
            response.put("recommendations", recommendations);
            response.put("count", recommendations.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("유의어 추천 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "유의어 추천 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 유의어가 없는 term들을 일괄 추천
     */
    @PostMapping("/term-synonyms/batch-recommend")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> batchRecommendSynonyms(@RequestBody Map<String, Integer> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            Integer limit = request.getOrDefault("limit", 10);

            log.info("일괄 유의어 추천 시작 (limit: {})", limit);

            // 1. 모든 term 조회
            List<com.newcodes7.small_town.global.entity.Term> allTerms = termRepository.findAll();

            // 2. 유의어가 없고, 이전에 거부되지 않은 term 필터링
            List<String> termsWithoutSynonyms = new ArrayList<>();
            List<Long> selectedTermIds = new ArrayList<>();
            for (com.newcodes7.small_town.global.entity.Term term : allTerms) {
                // hasTranslation이 false면 이미 추천받았지만 선택되지 않은 것 -> 제외
                if (Boolean.FALSE.equals(term.getHasTranslation())) {
                    continue;
                }

                List<com.newcodes7.small_town.global.entity.TermSynonym> relations =
                    termSynonymService.getSynonymRelations(term.getId());

                if (relations.isEmpty() && termsWithoutSynonyms.size() < limit) {
                    termsWithoutSynonyms.add(term.getTerm());
                    selectedTermIds.add(term.getId());
                }
            }

            log.info("유의어가 없는 term {} 개 발견 (이전 거부 term 제외)", termsWithoutSynonyms.size());

            // 3. DeepL로 일괄 추천
            Map<String, List<String>> recommendations = translationService.batchRecommendSynonyms(termsWithoutSynonyms);

            // 4. 추천받은 term들을 hasTranslation=false로 설정 (기본값: 선택되지 않음)
            for (Long termId : selectedTermIds) {
                com.newcodes7.small_town.global.entity.Term term = termRepository.findById(termId).orElse(null);
                if (term != null) {
                    term.updateHasTranslation(false);
                    termRepository.save(term);
                }
            }

            response.put("success", true);
            response.put("processedCount", termsWithoutSynonyms.size());
            response.put("recommendations", recommendations);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("일괄 유의어 추천 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "일괄 추천 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ========== Tech Term Dictionary (StackOverflow + Wikipedia) ==========

    /**
     * StackOverflow 태그 수집 → DeepL 번역 → UserDictionary + TermSynonym 등록
     *
     * @param limit 가져올 태그 개수 (기본 100)
     * @param minCount 최소 사용 횟수 (기본 1000)
     * @param offset 건너뛸 태그 개수 (기본 0) - 이미 저장된 term 건너뛰기
     * @return 처리 결과
     */
    @GetMapping("/tech-terms/collect")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> collectTechTerms(
        @RequestParam(defaultValue = "100") int limit,
        @RequestParam(defaultValue = "1000") int minCount,
        @RequestParam(defaultValue = "0") int offset) {

        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Tech term collection started: limit={}, minCount={}, offset={}",
                    limit, minCount, offset);

            // 비동기로 실행 (시간이 오래 걸릴 수 있음)
            final int finalLimit = limit;
            final int finalMinCount = minCount;
            final int finalOffset = offset;

            new Thread(() -> {
                try {
                    TechTermService.TechTermCollectionResult result =
                            techTermService.collectAndProcessTechTerms(finalLimit, finalMinCount, finalOffset);

                    if (result.isSuccess()) {
                        log.info("Tech term collection completed successfully:");
                        log.info("  - Offset: {}", result.getOffset());
                        log.info("  - Fetched tags: {}", result.getFetchedTagsCount());
                        log.info("  - Translated: {}", result.getTranslatedCount());
                        log.info("  - Saved to dictionary: {}", result.getSavedDictionaryCount());
                        log.info("  - Saved synonyms: {}", result.getSavedSynonymCount());
                        log.info("  - Processing time: {}ms", result.getProcessingTimeMs());

                        // 결과 샘플 로깅
                        if (result.getTermPairs() != null && !result.getTermPairs().isEmpty()) {
                            log.info("Sample term pairs:");
                            result.getTermPairs().stream()
                                    .limit(10)
                                    .forEach(pair -> log.info("  - {} ({}) → {}",
                                            pair.getEnglishTerm(),
                                            pair.getOriginalTag(),
                                            pair.getKoreanTerm()));
                        }
                    } else {
                        log.error("Tech term collection failed: {}", result.getErrorMessage());
                    }

                } catch (Exception e) {
                    log.error("Tech term collection background job failed", e);
                }
            }).start();

            response.put("success", true);
            response.put("message", String.format(
                    "기술 용어 수집 작업이 시작되었습니다. (limit: %d, minCount: %d, offset: %d) 로그를 확인해주세요.",
                    limit, minCount, offset
            ));
            response.put("limit", limit);
            response.put("minCount", minCount);
            response.put("offset", offset);
            response.put("nextOffset", offset + limit);
            response.put("hint", "다음 배치는 offset=" + (offset + limit) + "으로 호출하세요.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Tech term collection start failed: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "기술 용어 수집 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ========== DeepL 번역 Term 모니터링 ==========

    /**
     * DeepL 번역 항목 목록 조회 API
     */
    @GetMapping("/translated-terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getTranslatedTerms() {
        Map<String, Object> response = new HashMap<>();

        try {
            List<com.newcodes7.small_town.global.entity.UserDictionary> entries =
                    userDictionaryRepository.findDeeplTranslatedEntries();

            List<Map<String, Object>> items = new ArrayList<>();
            for (com.newcodes7.small_town.global.entity.UserDictionary entry : entries) {
                String koreanTerm = entry.getWord();
                String englishTerm = entry.getReason().replace("DeepL translation of: ", "");

                Optional<com.newcodes7.small_town.global.entity.Term> koreanTermEntity =
                        termRepository.findByTermAndTermType(koreanTerm, "NNG");
                Optional<com.newcodes7.small_town.global.entity.Term> englishTermEntity =
                        termRepository.findByTermAndTermType(englishTerm, "NNG");

                boolean hasTermEntity = koreanTermEntity.isPresent();
                Long termId = hasTermEntity ? koreanTermEntity.get().getId() : null;

                boolean hasSynonym = false;
                Long synonymId = null;
                if (koreanTermEntity.isPresent() && englishTermEntity.isPresent()) {
                    Optional<com.newcodes7.small_town.global.entity.TermSynonym> synonym =
                            termSynonymService.findSynonymByTermIds(
                                    koreanTermEntity.get().getId(),
                                    englishTermEntity.get().getId());
                    if (synonym.isPresent()) {
                        hasSynonym = true;
                        synonymId = synonym.get().getId();
                    }
                }

                Map<String, Object> item = new HashMap<>();
                item.put("id", entry.getId());
                item.put("koreanTerm", koreanTerm);
                item.put("englishTerm", englishTerm);
                item.put("hasTermEntity", hasTermEntity);
                item.put("termId", termId);
                item.put("hasSynonym", hasSynonym);
                item.put("synonymId", synonymId);
                item.put("createdAt", entry.getCreatedAt());
                items.add(item);
            }

            response.put("success", true);
            response.put("items", items);
            response.put("totalCount", items.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("DeepL 번역 항목 조회 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "번역 항목 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * DeepL 번역 항목 수정 API
     */
    @PutMapping("/translated-terms/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateTranslatedTerm(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            com.newcodes7.small_town.global.entity.UserDictionary userDict =
                    userDictionaryRepository.findById(id)
                            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 번역 항목입니다. ID: " + id));

            String oldKoreanTerm = userDict.getWord();
            String englishTerm = userDict.getReason().replace("DeepL translation of: ", "");
            String newKoreanTerm = request.get("newKoreanTerm");

            if (newKoreanTerm == null || newKoreanTerm.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "새 한국어 번역이 필요합니다.");
                return ResponseEntity.badRequest().body(response);
            }
            newKoreanTerm = newKoreanTerm.trim();

            // 기존 Synonym 삭제
            Optional<com.newcodes7.small_town.global.entity.Term> oldKoreanTermEntity =
                    termRepository.findByTermAndTermType(oldKoreanTerm, "NNG");
            Optional<com.newcodes7.small_town.global.entity.Term> englishTermEntity =
                    termRepository.findByTermAndTermType(englishTerm, "NNG");

            if (oldKoreanTermEntity.isPresent() && englishTermEntity.isPresent()) {
                Optional<com.newcodes7.small_town.global.entity.TermSynonym> existingSynonym =
                        termSynonymService.findSynonymByTermIds(
                                oldKoreanTermEntity.get().getId(),
                                englishTermEntity.get().getId());
                existingSynonym.ifPresent(s -> termSynonymService.deleteSynonym(s.getId()));
            }

            // UserDictionary 업데이트
            userDict.updateWord(newKoreanTerm);
            userDictionaryRepository.save(userDict);

            // 새 Synonym 생성 (Term auto-create 포함)
            termSynonymService.addSynonymByTermString(newKoreanTerm, englishTerm);

            response.put("success", true);
            response.put("message", String.format("번역이 '%s'에서 '%s'로 수정되었습니다.", oldKoreanTerm, newKoreanTerm));

            log.info("DeepL 번역 항목 수정: {} → {} (영어: {})", oldKoreanTerm, newKoreanTerm, englishTerm);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("DeepL 번역 항목 수정 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "번역 항목 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * DeepL 번역 항목 삭제 API
     */
    @DeleteMapping("/translated-terms/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteTranslatedTerm(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            com.newcodes7.small_town.global.entity.UserDictionary userDict =
                    userDictionaryRepository.findById(id)
                            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 번역 항목입니다. ID: " + id));

            String koreanTerm = userDict.getWord();
            String englishTerm = userDict.getReason().replace("DeepL translation of: ", "");

            // Synonym 삭제
            Optional<com.newcodes7.small_town.global.entity.Term> koreanTermEntity =
                    termRepository.findByTermAndTermType(koreanTerm, "NNG");
            Optional<com.newcodes7.small_town.global.entity.Term> englishTermEntity =
                    termRepository.findByTermAndTermType(englishTerm, "NNG");

            if (koreanTermEntity.isPresent() && englishTermEntity.isPresent()) {
                termSynonymService.findSynonymByTermIds(
                        koreanTermEntity.get().getId(),
                        englishTermEntity.get().getId())
                        .ifPresent(s -> termSynonymService.deleteSynonym(s.getId()));
            }

            // UserDictionary 삭제 (Term 엔티티는 유지)
            userDictionaryRepository.delete(userDict);

            response.put("success", true);
            response.put("message", String.format("번역 항목 '%s' (%s)이 삭제되었습니다.", koreanTerm, englishTerm));

            log.info("DeepL 번역 항목 삭제: {} ({}) ID: {}", koreanTerm, englishTerm, id);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("DeepL 번역 항목 삭제 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "번역 항목 삭제 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * StackOverflow 태그 미리보기 (실제 저장하지 않음)
     *
     * @param limit 가져올 태그 개수
     * @param minCount 최소 사용 횟수
     * @return 태그 목록
     */
    @GetMapping("/tech-terms/preview")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> previewStackOverflowTags(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "1000") int minCount) {

        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Previewing StackOverflow tags: limit={}, minCount={}", limit, minCount);

            // StackOverflow 태그 가져오기
            List<com.newcodes7.small_town.term.dto.StackExchangeTagDto> tags =
                    stackExchangeApiService.fetchPopularTags(limit, minCount);

            // DTO로 변환
            List<Map<String, Object>> tagDataList = new ArrayList<>();
            for (com.newcodes7.small_town.term.dto.StackExchangeTagDto tag : tags) {
                Map<String, Object> tagData = new HashMap<>();
                tagData.put("name", tag.getName());
                tagData.put("searchTerm", stackExchangeApiService.convertTagToSearchTerm(tag.getName()));
                tagData.put("count", tag.getCount());
                tagData.put("hasSynonyms", tag.getHasSynonyms());
                tagDataList.add(tagData);
            }

            response.put("success", true);
            response.put("totalFetched", tags.size());
            response.put("tags", tagDataList);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Preview failed: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "미리보기 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
