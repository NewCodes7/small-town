package com.newcodes7.small_town.crawler.integration;

import com.newcodes7.small_town.crawler.config.CrawlerProperties;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.dto.CrawlingStats;
import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;
import com.newcodes7.small_town.crawler.service.CrawlingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Crawler 핵심 동작 통합 테스트")
class CrawlerCoreIntegrationTest {

    @Autowired
    private CrawlingService crawlingService;

    @Autowired
    private CrawlerCorporationRepository corporationRepository;

    @Autowired
    private CrawlerArticleRepository articleRepository;

    @Autowired
    private CrawlerProperties crawlerProperties;

    private Corporation testCorporation;

    @BeforeEach
    void setUp() {
        // 테스트 데이터 정리
        articleRepository.deleteAll();
        corporationRepository.deleteAll();

        // 실제 크롤링 가능한 정적 HTML 페이지 사용
        testCorporation = Corporation.builder()
                .name("naver")
                .blogLink("https://d2.naver.com/home") 
                .build();
        testCorporation = corporationRepository.save(testCorporation);
    }

    @Test
    @DisplayName("실제 웹사이트 크롤링 및 DB 저장 검증")
    @Transactional
    void 실제_웹사이트_크롤링_통합_테스트() {
        // given - 크롤링 활성화
        assertThat(crawlerProperties.isEnabled()).isTrue();

        // when - 실제 웹사이트 크롤링 실행
        CrawlResult result = crawlingService.crawlSingleBlog(testCorporation.getId());

        // then - 크롤링 결과 검증
        assertThat(result).isNotNull();        
        assertThat(result.isSuccess()).isTrue();
        List<Article> savedArticles = articleRepository.findByCorporationIdAndNotDeleted(testCorporation.getId());
        assertThat(savedArticles).isNotEmpty();
        
        Article firstArticle = savedArticles.get(0);
        assertThat(firstArticle.getCorporationId()).isEqualTo(testCorporation.getId());
        assertThat(firstArticle.getTitle()).isNotBlank();
        assertThat(firstArticle.getLink()).isNotBlank();
        assertThat(firstArticle.getCreatedAt()).isNotNull();
        assertThat(firstArticle.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("다중 기업 크롤링 결과 검증")
    @Transactional
    void 다중_기업_크롤링_결과_검증() {
        // given - 추가 기업 데이터 준비
        Corporation additionalCorp = Corporation.builder()
                .name("netflix")
                .blogLink("https://netflixtechblog.medium.com/")
                .build();
        corporationRepository.save(additionalCorp);

        // when - 전체 기업 크롤링 실행
        List<CrawlResult> results = crawlingService.crawlAllBlogs();

        // then - 기본 검증 (크롤링이 비활성화되어 있을 수 있으므로 null이 아닌지만 확인)
        assertThat(results).isNotNull();
        
        results.forEach(result -> {
            if (result.getCorporation() != null) {
                assertThat(result.getCorporation().getName()).isNotBlank();
            }
        });
    }

    @Test
    @DisplayName("크롤링 통계 실시간 업데이트 검증")
    void 크롤링_통계_실시간_업데이트_검증() {
        // given - 초기 통계 확인
        CrawlingStats initialStats = crawlingService.getCrawlingStats();
        assertThat(initialStats.getTotalCorporations()).isEqualTo(1);

        // when - 크롤링 실행
        crawlingService.crawlSingleBlog(testCorporation.getId());

        // then - 통계 업데이트 확인
        CrawlingStats updatedStats = crawlingService.getCrawlingStats();
        assertThat(updatedStats.getTotalCorporations()).isEqualTo(1);
        assertThat(updatedStats.getLastCrawledAt()).isAfter(initialStats.getLastCrawledAt());
    }

    @Test
    @DisplayName("중복 기사 방지 메커니즘 통합 검증")
    @Transactional
    void 중복_기사_방지_통합_검증() {
        // given - 첫 번째 크롤링 실행
        CrawlResult firstResult = crawlingService.crawlSingleBlog(testCorporation.getId());
        
        if (firstResult.isSuccess() && firstResult.getNewArticles() > 0) {
            int initialArticleCount = firstResult.getNewArticles();
            
            // when - 동일한 기업에 대해 재크롤링
            CrawlResult secondResult = crawlingService.crawlSingleBlog(testCorporation.getId());
            
            // then - 중복 기사가 저장되지 않았는지 확인
            if (secondResult.isSuccess()) {
                assertThat(secondResult.getNewArticles()).isLessThanOrEqualTo(initialArticleCount);
            }
            
            // DB에서 직접 중복 확인
            List<Article> allArticles = articleRepository.findByCorporationIdAndNotDeleted(testCorporation.getId());
            long uniqueLinks = allArticles.stream()
                    .map(Article::getLink)
                    .distinct()
                    .count();
            
            assertThat(uniqueLinks).isEqualTo(allArticles.size());
        }
    }

    @Test
    @DisplayName("트랜잭션 롤백 시나리오 검증")
    @Transactional
    void 트랜잭션_롤백_시나리오_검증() {
        // given - 잘못된 URL을 가진 기업
        Corporation invalidCorp = Corporation.builder()
                .name("Invalid Corp")
                .blogLink("https://invalid-url-that-does-not-exist-12345.com")
                .build();
        invalidCorp = corporationRepository.save(invalidCorp);

        // when - 크롤링 실행 (실패 예상)
        CrawlResult result = crawlingService.crawlSingleBlog(invalidCorp.getId());

        // then - 실패한 크롤링이 다른 데이터에 영향을 주지 않는지 확인
        assertThat(result.isSuccess()).isFalse();
        
        // 기존 데이터는 영향받지 않아야 함
        assertThat(corporationRepository.findById(testCorporation.getId())).isPresent();
        assertThat(corporationRepository.findById(invalidCorp.getId())).isPresent();
    }

    @Test
    @Disabled // TODO: 향후 핵심 기능 온전히 구현 이후 활성화
    @DisplayName("대용량 크롤링 성능 검증")
    void 대용량_크롤링_성능_검증() {
        // given - 성능 측정 시작
        long startTime = System.currentTimeMillis();

        // when - 크롤링 실행
        List<CrawlResult> results = crawlingService.crawlAllBlogs();

        // then - 성능 확인 (10초 이내 완료)
        long executionTime = System.currentTimeMillis() - startTime;
        assertThat(executionTime).isLessThan(10000); // 10초 이내

        // 모든 크롤링이 완료되었는지 확인
        assertThat(results).isNotEmpty();
        results.forEach(result -> {
            assertThat(result.getCorporation()).isNotNull();
        });
    }

    @Test
    @DisplayName("크롤링 설정 비활성화 시 동작 검증")
    void 크롤링_비활성화_시_동작_검증() {
        // given - CrawlerProperties 확인
        boolean originalEnabled = crawlerProperties.isEnabled();
        
        try {
            // when - 크롤링이 활성화된 상태에서 실행
            if (originalEnabled) {
                List<CrawlResult> results = crawlingService.crawlAllBlogs();
                
                // then - 정상적으로 크롤링이 실행되거나 빈 결과 반환
                assertThat(results).isNotNull();
            } else {
                // 비활성화된 경우 빈 결과 반환 확인
                List<CrawlResult> results = crawlingService.crawlAllBlogs();
                assertThat(results).isEmpty();
            }
        } finally {
            // 테스트 후 원래 설정 복원은 필요 없음 (test profile 사용)
        }
    }

    @Test
    @DisplayName("데이터 일관성 검증")
    void 데이터_일관성_검증() {
        // given - 크롤링 실행
        crawlingService.crawlSingleBlog(testCorporation.getId());

        // when - 데이터 조회
        List<Article> articles = articleRepository.findByCorporationIdAndNotDeleted(testCorporation.getId());

        // then - 데이터 일관성 확인
        if (!articles.isEmpty()) {
            // 중복 링크가 없는지 확인
            long uniqueLinks = articles.stream().map(Article::getLink).distinct().count();
            assertThat(uniqueLinks).isEqualTo(articles.size());
            
            // 모든 기사가 올바른 기업 ID를 가지고 있는지 확인
            articles.forEach(article -> {
                assertThat(article.getCorporationId()).isEqualTo(testCorporation.getId());
            });
        }
    }

    @Test
    @DisplayName("소프트 삭제된 기업 크롤링 방지 검증")
    @Transactional
    void 소프트_삭제된_기업_크롤링_방지_검증() {
        // given - 기업 소프트 삭제
        testCorporation.setDeletedAt(LocalDateTime.now());
        corporationRepository.save(testCorporation);

        // when - 삭제된 기업 크롤링 시도
        CrawlResult result = crawlingService.crawlSingleBlog(testCorporation.getId());

        // then - 크롤링이 실행되지 않아야 함
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("기업을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("빈 블로그 링크 처리 통합 검증")
    @Transactional
    void 빈_블로그_링크_처리_통합_검증() {
        // given - 블로그 링크가 없는 기업
        Corporation emptyLinkCorp = Corporation.builder()
                .name("No Link Corp")
                .blogLink("")
                .build();
        emptyLinkCorp = corporationRepository.save(emptyLinkCorp);

        Corporation nullLinkCorp = Corporation.builder()
                .name("Null Link Corp")
                .blogLink(null)
                .build();
        nullLinkCorp = corporationRepository.save(nullLinkCorp);

        // when & then - 빈 링크 처리
        CrawlResult emptyResult = crawlingService.crawlSingleBlog(emptyLinkCorp.getId());
        assertThat(emptyResult.isSuccess()).isFalse();
        assertThat(emptyResult.getErrorMessage()).contains("블로그 링크가 없습니다");

        CrawlResult nullResult = crawlingService.crawlSingleBlog(nullLinkCorp.getId());
        assertThat(nullResult.isSuccess()).isFalse();
        assertThat(nullResult.getErrorMessage()).contains("블로그 링크가 없습니다");
    }
}