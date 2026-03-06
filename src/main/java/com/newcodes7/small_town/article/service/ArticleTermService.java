package com.newcodes7.small_town.article.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Collection;

import jakarta.persistence.EntityManager;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.ArticleTermRepository;
import com.newcodes7.small_town.article.repository.StopwordRepository;
import com.newcodes7.small_town.article.repository.TermRepository;
import com.newcodes7.small_town.article.repository.TermSynonymRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.ArticleTerm;
import com.newcodes7.small_town.global.entity.Stopword;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.entity.TermSource;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer;
import com.newcodes7.small_town.global.service.UnifiedMorphemeAnalyzer;
import com.newcodes7.small_town.global.util.KoreanCharacterUtil;
import com.newcodes7.small_town.video.repository.VideoTermRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleTermService {

    private final ArticleRepository articleRepository;
    private final ArticleTermRepository articleTermRepository;
    private final VideoTermRepository videoTermRepository;
    private final TermRepository termRepository;
    private final TermSynonymRepository termSynonymRepository;
    private final StopwordRepository stopwordRepository;
    private final UnifiedMorphemeAnalyzer unifiedMorphemeAnalyzer;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;

    /**
     * 모든 article의 term을 추출하고 저장
     * 이미 term이 있는 article은 건너뜀
     */
    public ArticleTermExtractionResult extractAndSaveAllArticleTerms() {
        return extractAndSaveAllArticleTerms(true);
    }

    /**
     * 모든 article의 term을 추출하고 저장
     * 배치 단위(100개)로 트랜잭션 커밋하여 메모리 효율성 향상
     *
     * @param forceReanalyze true면 이미 term이 있어도 재분석, false면 건너뜀
     */
    public ArticleTermExtractionResult extractAndSaveAllArticleTerms(boolean forceReanalyze) {
        return extractAndSaveAllArticleTerms(forceReanalyze, null);
    }

    /**
     * article의 term을 추출하고 저장
     * 배치 단위(100개)로 트랜잭션 커밋하여 메모리 효율성 향상
     *
     * @param forceReanalyze true면 이미 term이 있어도 재분석, false면 건너뜀
     * @param maxArticleId null이 아니면 해당 ID 이하의 article만 처리
     */
    public ArticleTermExtractionResult extractAndSaveAllArticleTerms(boolean forceReanalyze, Long maxArticleId) {
        String mode = forceReanalyze ? "강제 재분석" : "기존 term이 있는 article은 건너뜀";
        String range = maxArticleId != null ? ", maxArticleId=" + maxArticleId : "";
        log.info("article term 추출 시작 ({}{})", mode, range);
        long startTime = System.currentTimeMillis();

        ArticleTermExtractionResult result = new ArticleTermExtractionResult();
        final int BATCH_SIZE = 50;
        int page = 0;

        while (true) {
            Pageable pageable = PageRequest.of(page, BATCH_SIZE, Sort.by("id").ascending());
            Page<Article> articles = maxArticleId != null
                    ? articleRepository.findByDeletedAtIsNullAndIdLessThanEqual(maxArticleId, pageable)
                    : articleRepository.findByDeletedAtIsNull(pageable);

            if (articles.isEmpty()) {
                break;
            }

            // 배치 단위로 트랜잭션 처리
            List<Article> articleList = articles.getContent();
            BatchResult batchResult = transactionTemplate.execute(status -> {
                BatchResult br = new BatchResult();
                for (Article article : articleList) {
                    try {
                        // 강제 재분석이 아니고 이미 term이 있으면 건너뛰기
                        if (!forceReanalyze && articleTermRepository.existsByArticleId(article.getId())) {
                            br.skipped++;
                            continue;
                        }

                        int termCount = extractAndSaveTermsForArticleInternal(article);
                        br.processed++;
                        br.terms += termCount;
                    } catch (Exception e) {
                        log.error("Article ID {} term 추출 실패: {}", article.getId(), e.getMessage(), e);
                        br.failed++;
                    }
                }
                return br;
            });

            if (batchResult != null) {
                result.addProcessedArticles(batchResult.processed);
                result.addSkippedArticles(batchResult.skipped);
                result.addFailedArticles(batchResult.failed);
                result.addTermCount(batchResult.terms);
            }

            // 영속성 컨텍스트 초기화 (메모리 누적 방지)
            // flush는 transactionTemplate.execute() 커밋 시 자동 수행됨
            entityManager.clear();

            log.info("배치 {} 완료: 처리={}, 건너뜀={}, 실패={}, term={}",
                    page + 1, result.getProcessedArticles(), result.getSkippedArticles(),
                    result.getFailedArticles(), result.getTotalTerms());

            page++;

            if (!articles.hasNext()) {
                break;
            }
        }

        long endTime = System.currentTimeMillis();
        result.setProcessingTimeMs(endTime - startTime);

        log.info("모든 article term 추출 완료: 처리={}, 건너뜀={}, 실패={}, term={}, 소요시간={}ms",
                result.getProcessedArticles(), result.getSkippedArticles(),
                result.getFailedArticles(), result.getTotalTerms(),
                result.getProcessingTimeMs());

        // Term 통계 갱신 (별도 트랜잭션)
        if (result.getProcessedArticles() > 0) {
            transactionTemplate.execute(status -> {
                updateTermStatisticsInternal();
                return null;
            });
        }

        return result;
    }

    /**
     * 배치 처리 결과를 담는 내부 클래스
     */
    private static class BatchResult {
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        int terms = 0;
    }

    /**
     * 특정 article의 term을 추출하고 저장 (단일 article 처리용, 트랜잭션 포함)
     * Term 엔티티는 재사용하여 중복 저장하지 않음
     *
     * @param article 대상 article
     * @return 추출된 term 개수
     */
    @Transactional
    public int extractAndSaveTermsForArticle(Article article) {
        return extractAndSaveTermsForArticleInternal(article);
    }

    /**
     * 특정 article의 term을 추출하고 저장 (내부 메서드, 트랜잭션 없음)
     * 배치 처리 시 외부에서 트랜잭션을 관리할 때 사용
     *
     * @param article 대상 article
     * @return 추출된 term 개수
     */
    private int extractAndSaveTermsForArticleInternal(Article article) {
        // 기존 term 삭제
        articleTermRepository.deleteByArticleId(article.getId());

        // 가중치 상수
        final double TITLE_WEIGHT = 3.0;  // title, translatedTitle에서 나온 term의 가중치
        final double CONTENT_WEIGHT = 1.0; // content에서 나온 term의 가중치

        // term별 정보를 저장할 Map (term -> {termInfo, maxWeight})
        Map<String, TermData> termDataMap = new HashMap<>();

        // 1. title과 translatedTitle에서 term 추출 (높은 가중치)
        List<String> titleTexts = new ArrayList<>();
        if (article.getTitle() != null && !article.getTitle().trim().isEmpty()) {
            titleTexts.add(article.getTitle());
        }
        if (article.getTranslatedTitle() != null && !article.getTranslatedTitle().trim().isEmpty()) {
            titleTexts.add(article.getTranslatedTitle());
        }

        if (!titleTexts.isEmpty()) {
            Map<String, MorphemeAnalyzer.TermInfo> titleTermMap =
                unifiedMorphemeAnalyzer.extractTermsFromMultipleTexts(titleTexts);

            for (MorphemeAnalyzer.TermInfo termInfo : titleTermMap.values()) {
                String key = termInfo.getTerm() + ":" + termInfo.getTermType();
                termDataMap.put(key, new TermData(termInfo, TITLE_WEIGHT, termInfo.getFrequency(), TermSource.TITLE));
                log.debug("Title term 추출: {} (빈도: {}, 가중치: {}, source: TITLE)",
                    termInfo.getTerm(), termInfo.getFrequency(), TITLE_WEIGHT);
            }
        }

        // 2. content에서 term 추출 (기본 가중치)
        if (article.getContent() != null && !article.getContent().trim().isEmpty()) {
            List<String> contentTexts = new ArrayList<>();
            contentTexts.add(article.getContent());

            Map<String, MorphemeAnalyzer.TermInfo> contentTermMap =
                unifiedMorphemeAnalyzer.extractTermsFromMultipleTexts(contentTexts);

            for (MorphemeAnalyzer.TermInfo termInfo : contentTermMap.values()) {
                String key = termInfo.getTerm() + ":" + termInfo.getTermType();
                TermData existingData = termDataMap.get(key);

                if (existingData != null) {
                    // 이미 title에서 추출된 term이면 빈도수만 합산, source를 BOTH로 변경
                    existingData.frequency += termInfo.getFrequency();
                    existingData.source = TermSource.BOTH;
                    log.debug("중복 term 발견 (title+content): {} (빈도 합산: {}, source: BOTH)",
                        termInfo.getTerm(), existingData.frequency);
                } else {
                    // content에서만 나온 새로운 term
                    termDataMap.put(key, new TermData(termInfo, CONTENT_WEIGHT, termInfo.getFrequency(), TermSource.CONTENT));
                    log.debug("Content term 추출: {} (빈도: {}, 가중치: {}, source: CONTENT)",
                        termInfo.getTerm(), termInfo.getFrequency(), CONTENT_WEIGHT);
                }
            }
        }

        // 3. term이 하나도 없으면 종료
        if (termDataMap.isEmpty()) {
            log.warn("Article ID {} has no extractable terms from title, translatedTitle, or content",
                article.getId());
            return 0;
        }

        // 4. 필터링: 최소 2회 반복된 term만 저장 (BM25 IDF 정합성을 위해 개수 제한 없음)
        final int MIN_FREQUENCY = 2;

        List<TermData> filteredTerms = termDataMap.values().stream()
                .filter(td -> td.frequency >= MIN_FREQUENCY)
                .sorted((a, b) -> Double.compare(b.frequency * b.weight, a.frequency * a.weight))
                .toList();

        if (filteredTerms.isEmpty()) {
            log.info("Article ID {} - 필터링 후 저장할 term 없음 (최소 빈도: {})", article.getId(), MIN_FREQUENCY);
            return 0;
        }

        // 1. 처리할 모든 단어 텍스트 추출
        Set<String> termNames = filteredTerms.stream()
                .map(t -> t.termInfo.getTerm())
                .collect(Collectors.toSet());

        // 2. 이미 존재하는 Term들을 한 번에 조회 (Map으로 만들어 접근 속도 향상)
        Map<String, Term> existingTermMap = termRepository.findByTermIn(termNames).stream()
                .collect(Collectors.toMap(Term::getTerm, t -> t, (t1, t2) -> t1));

        List<Term> newTermsToSave = new ArrayList<>();
        List<ArticleTerm> articleTerms = new ArrayList<>();

        for (TermData termData : filteredTerms) {
            MorphemeAnalyzer.TermInfo termInfo = termData.termInfo;
            String termText = termInfo.getTerm();

            if (termText.length() > 100) continue;

            // 3. 한글 처리 (필요한 경우에만)
            String decomposed = KoreanCharacterUtil.containsHangul(termText) ? KoreanCharacterUtil.decomposeHangul(termText) : null;
            String chosung = KoreanCharacterUtil.containsHangul(termText) ? KoreanCharacterUtil.extractChosung(termText) : null;

            // 4. 기존 Term 확인 및 업데이트 혹은 생성
            Term term = existingTermMap.get(termText);
            
            if (term == null) {
                // 신규 Term 객체 생성 (아직 save 안 함)
                term = Term.builder()
                        .term(termText)
                        .termType(termInfo.getTermType())
                        .decomposedTerm(decomposed)
                        .chosung(chosung)
                        .build();
                newTermsToSave.add(term);
                // Map에 미리 넣어 중복 생성 방지
                existingTermMap.put(termText, term); 
            } else {
                // 기존 Term 업데이트 로직 (필요 시 더티 체킹 활용 가능)
                if ((term.getDecomposedTerm() == null && decomposed != null) || (term.getChosung() == null && chosung != null)) {
                    term.updateDecomposed(decomposed, chosung); // 별도의 update 메서드 사용 추천
                }
            }

            // 5. ArticleTerm 리스트 추가
            double score = termData.frequency * termData.weight;
            articleTerms.add(ArticleTerm.builder()
                    .article(article)
                    .term(term)
                    .frequency(termData.frequency)
                    .score(score)
                    .source(termData.source)
                    .build());
        }

        // 6. DB 일괄 저장 (Transaction 내부에서 실행)
        termRepository.saveAll(newTermsToSave); // 신규 단어들 저장
        articleTermRepository.saveAll(articleTerms); // 관계 테이블 저장

        log.info("Article ID {} term 저장 완료: {} terms (전체: {}, 최소빈도 {} 이상: {})",
            article.getId(), articleTerms.size(), termDataMap.size(),
            MIN_FREQUENCY, filteredTerms.size());

        return articleTerms.size();
    }

    /**
     * 특정 article ID의 term 조회
     */
    @Transactional(readOnly = true)
    public List<ArticleTerm> getArticleTerms(Long articleId) {
        return articleTermRepository.findByArticleId(articleId);
    }

    /**
     * Term 삭제 및 불용어 등록
     * 특정 term을 삭제하고, 해당 term을 불용어로 등록하여 다시 추출되지 않도록 함
     *
     * @param termId 삭제할 term ID
     * @param reason 불용어 등록 사유 (선택사항)
     * @return 삭제된 ArticleTerm 및 VideoTerm 총 개수
     */
    @Transactional
    public int deleteTermAndAddToStopwords(Long termId, String reason) {
        // Term 조회
        Term term = termRepository.findById(termId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 Term입니다. ID: " + termId));

        // 1. 해당 term이 포함된 모든 TermSynonym 삭제 (외래 키 제약 조건 만족)
        int synonymCount = termSynonymRepository.deleteAllByTermId(termId);
        if (synonymCount > 0) {
            log.info("TermSynonym {} 개 삭제 완료", synonymCount);
        }

        // 2. 해당 term을 사용하는 모든 ArticleTerm 삭제 (최적화된 쿼리 사용)
        int articleTermCount = articleTermRepository.countByTermId(termId);
        articleTermRepository.deleteByTermId(termId);

        // 3. 해당 term을 사용하는 모든 VideoTerm 삭제 (최적화된 쿼리 사용)
        int videoTermCount = videoTermRepository.countByTermId(termId);
        videoTermRepository.deleteByTermId(termId);

        int totalDeletedCount = articleTermCount + videoTermCount;

        // 4. Term을 불용어로 등록
        if (!stopwordRepository.existsByTermAndTermType(term.getTerm(), term.getTermType())) {
            Stopword stopword = Stopword.builder()
                    .term(term.getTerm())
                    .termType(term.getTermType())
                    .reason(reason)
                    .build();
            stopwordRepository.save(stopword);
            log.info("Term을 불용어로 등록: {} ({}), 사유: {}", term.getTerm(), term.getTermType(), reason);
        } else {
            log.info("Term이 이미 불용어로 등록되어 있음: {} ({})", term.getTerm(), term.getTermType());
        }

        // 5. Term 삭제
        termRepository.delete(term);

        // 6. 불용어 캐시 갱신
        unifiedMorphemeAnalyzer.refreshStopwordCache();

        log.info("Term 삭제 완료: {} ({}), TermSynonym {} 개, ArticleTerm {} 개, VideoTerm {} 개 삭제",
                term.getTerm(), term.getTermType(), synonymCount, articleTermCount, videoTermCount);

        return totalDeletedCount;
    }

    /**
     * 특정 term 문자열을 불용어로 등록
     *
     * @param termStr term 문자열
     * @param termType 품사
     * @param reason 불용어 등록 사유
     */
    @Transactional
    public void addStopword(String termStr, String termType, String reason) {
        if (!stopwordRepository.existsByTermAndTermType(termStr, termType)) {
            Stopword stopword = Stopword.builder()
                    .term(termStr)
                    .termType(termType)
                    .reason(reason)
                    .build();
            stopwordRepository.save(stopword);

            // 불용어 캐시 갱신
            unifiedMorphemeAnalyzer.refreshStopwordCache();

            log.info("불용어 등록: {} ({}), 사유: {}", termStr, termType, reason);
        }
    }

    /**
     * 최신 article부터 지정된 개수만큼 term을 추출하고 저장
     *
     * @param limit 추출할 article 개수
     * @param forceReanalyze true면 이미 term이 있어도 재분석, false면 term이 없는 것만
     * @return 추출 결과
     */
    @Transactional
    public ArticleTermExtractionResult extractLatestArticleTerms(int limit, boolean forceReanalyze) {
        log.info("최신 article {} 개 term 추출 시작 (강제재분석: {})", limit, forceReanalyze);
        long startTime = System.currentTimeMillis();

        ArticleTermExtractionResult result = new ArticleTermExtractionResult();
        List<Long> processedArticleIds = new ArrayList<>();

        // 최신순으로 정렬하여 조회
        Pageable pageable = PageRequest.of(0, limit, Sort.by("publishedAt").descending());
        Page<Article> articles = articleRepository.findByDeletedAtIsNull(pageable);

        log.info("조회된 article 개수: {}", articles.getContent().size());

        for (Article article : articles) {
            try {
                // 강제 재분석이 아니고 이미 term이 있으면 건너뛰기
                if (!forceReanalyze && articleTermRepository.existsByArticleId(article.getId())) {
                    result.incrementSkippedArticles();
                    log.debug("Article ID {} - 이미 term이 존재하여 건너뜀", article.getId());
                    continue;
                }

                int termCount = extractAndSaveTermsForArticleInternal(article);
                result.incrementProcessedArticles();
                result.addTermCount(termCount);
                processedArticleIds.add(article.getId());

                log.info("Article ID {} term 추출 완료: {} terms", article.getId(), termCount);
            } catch (Exception e) {
                log.error("Article ID {} term 추출 실패: {}", article.getId(), e.getMessage(), e);
                result.incrementFailedArticles();
            }
        }

        long endTime = System.currentTimeMillis();
        result.setProcessingTimeMs(endTime - startTime);

        log.info("최신 article term 추출 완료: 처리={}, 건너뜀={}, 실패={}, term={}, 소요시간={}ms",
                result.getProcessedArticles(), result.getSkippedArticles(),
                result.getFailedArticles(), result.getTotalTerms(),
                result.getProcessingTimeMs());

        // Term 통계 갱신 (처리된 article들의 관련 term만 선택적 업데이트)
        if (!processedArticleIds.isEmpty()) {
            log.info("처리된 {} 개 article 관련 Term 통계 갱신 시작", processedArticleIds.size());
            int updatedCount = termRepository.updateTermStatisticsByArticleIds(processedArticleIds);
            log.info("Term 통계 갱신 완료: {} 개 term 업데이트", updatedCount);
        }

        return result;
    }

    /**
     * 단일 article의 term을 추출하고 저장
     *
     * @param articleId article ID
     * @return 추출 결과
     */
    @Transactional
    public ArticleTermExtractionResult extractTermsForSingleArticle(Long articleId) {
        log.info("Article ID {} term 추출 시작", articleId);
        long startTime = System.currentTimeMillis();

        ArticleTermExtractionResult result = new ArticleTermExtractionResult();

        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 Article입니다. ID: " + articleId));

        try {
            int termCount = extractAndSaveTermsForArticle(article);
            result.incrementProcessedArticles();
            result.addTermCount(termCount);

            log.info("Article ID {} term 추출 완료: {} terms", articleId, termCount);
        } catch (Exception e) {
            log.error("Article ID {} term 추출 실패: {}", articleId, e.getMessage(), e);
            result.incrementFailedArticles();
            throw e;
        }

        long endTime = System.currentTimeMillis();
        result.setProcessingTimeMs(endTime - startTime);

        // Term 통계 갱신 (단일 article이므로 관련 term만 갱신)
        updateTermStatisticsForArticle(article);

        return result;
    }

    /**
     * 모든 Term의 통계 재계산 (total_frequency, article_count)
     * 대량 작업 후 일괄 갱신
     */
    @Transactional
    public void updateTermStatistics() {
        updateTermStatisticsInternal();
    }

    /**
     * 모든 Term의 통계 재계산 (내부 메서드, 트랜잭션 없음)
     */
    private void updateTermStatisticsInternal() {
        log.info("모든 Term 통계 갱신 시작...");
        long startTime = System.currentTimeMillis();

        // 전체 통계 갱신은 대량 UPDATE로 오래 걸릴 수 있으므로 현재 트랜잭션에서 타임아웃 비활성화
        entityManager.createNativeQuery("SET LOCAL statement_timeout = 0").executeUpdate();

        // 1. ArticleTerm이 있는 Term들의 통계 업데이트
        int updatedCount = termRepository.updateAllTermStatistics();
        log.info("ArticleTerm이 있는 Term 통계 업데이트 완료: {} 개", updatedCount);

        // 2. ArticleTerm이 없는 Term들의 통계 초기화 (0으로 설정)
        int resetCount = termRepository.resetOrphanedTermStatistics();
        log.info("ArticleTerm이 없는 Term 통계 초기화 완료: {} 개", resetCount);

        long endTime = System.currentTimeMillis();
        log.info("모든 Term 통계 갱신 완료: 소요시간={}ms", endTime - startTime);
    }

    /**
     * 특정 Article과 관련된 Term들의 통계만 갱신
     * 단일 article 처리 시 사용
     * IN 절을 사용하여 한 번의 쿼리로 모든 관련 term 업데이트 (N+1 방지)
     */
    @Transactional
    public void updateTermStatisticsForArticle(Article article) {
        List<ArticleTerm> articleTerms = articleTermRepository.findByArticleId(article.getId());

        if (articleTerms.isEmpty()) {
            log.debug("Article ID {} has no terms to update statistics", article.getId());
            return;
        }

        // IN 절을 사용하여 한 번에 업데이트 (기존: N개 쿼리 → 개선: 1개 쿼리)
        List<Long> termIds = articleTerms.stream()
                .map(at -> at.getTerm().getId())
                .toList();

        log.debug("Article ID {} 관련 Term 통계 갱신 시작: {} 개 term", article.getId(), termIds.size());

        int updatedCount = termRepository.updateTermStatisticsByIds(termIds);

        log.debug("Article ID {} 관련 Term 통계 갱신 완료: {} 개 업데이트", article.getId(), updatedCount);
    }

    /**
     * Term 추출 결과 DTO
     */
    public static class ArticleTermExtractionResult {
        private int processedArticles = 0;
        private int skippedArticles = 0;
        private int failedArticles = 0;
        private int totalTerms = 0;
        private long processingTimeMs = 0;

        public void incrementProcessedArticles() {
            this.processedArticles++;
        }

        public void addProcessedArticles(int count) {
            this.processedArticles += count;
        }

        public void incrementSkippedArticles() {
            this.skippedArticles++;
        }

        public void addSkippedArticles(int count) {
            this.skippedArticles += count;
        }

        public void incrementFailedArticles() {
            this.failedArticles++;
        }

        public void addFailedArticles(int count) {
            this.failedArticles += count;
        }

        public void addTermCount(int count) {
            this.totalTerms += count;
        }

        public int getProcessedArticles() {
            return processedArticles;
        }

        public int getSkippedArticles() {
            return skippedArticles;
        }

        public int getFailedArticles() {
            return failedArticles;
        }

        public int getTotalTerms() {
            return totalTerms;
        }

        public long getProcessingTimeMs() {
            return processingTimeMs;
        }

        public void setProcessingTimeMs(long processingTimeMs) {
            this.processingTimeMs = processingTimeMs;
        }
    }

    /**
     * Term 추출 시 사용되는 임시 데이터 클래스
     */
    private static class TermData {
        final MorphemeAnalyzer.TermInfo termInfo;
        final double weight;
        int frequency;
        TermSource source;

        TermData(MorphemeAnalyzer.TermInfo termInfo, double weight, int frequency, TermSource source) {
            this.termInfo = termInfo;
            this.weight = weight;
            this.frequency = frequency;
            this.source = source;
        }
    }
}
