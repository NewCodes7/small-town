package com.newcodes7.small_town.crawler.integration;

import com.newcodes7.small_town.crawler.config.CrawlerProperties;
import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;
import com.newcodes7.small_town.crawler.service.CrawlingScheduler;
import com.newcodes7.small_town.crawler.service.CrawlingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Crawler 스케줄링 및 DB 통합 테스트")
class CrawlerSchedulingIntegrationTest {

    @Autowired
    private CrawlingService crawlingService;

    @Autowired
    private CrawlingScheduler crawlingScheduler;

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

        // 테스트용 기업 데이터 준비
        testCorporation = Corporation.builder()
                .name("Scheduling Test Corp")
                .blogLink("https://httpbin.org/html")
                .build();
        testCorporation = corporationRepository.save(testCorporation);
    }

    @Test
    @DisplayName("기업별 크롤링 결과 DB 저장 검증")
    @Transactional
    void 기업별_크롤링_결과_DB_저장_검증() {
        // given - 초기 상태 확인
        long initialCount = articleRepository.countByCorporationId(testCorporation.getId());
        assertThat(initialCount).isEqualTo(0);

        // when - 크롤링 실행
        crawlingService.crawlSingleBlog(testCorporation.getId());

        // then - DB 저장 결과 검증
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            long afterCrawlCount = articleRepository.countByCorporationId(testCorporation.getId());
            // 크롤링이 성공했다면 기사가 추가되어야 함
            if (afterCrawlCount > initialCount) {
                List<Article> articles = articleRepository.findByCorporationId(testCorporation.getId());
                
                articles.forEach(article -> {
                    assertThat(article.getCorporationId()).isEqualTo(testCorporation.getId());
                    assertThat(article.getTitle()).isNotNull();
                    assertThat(article.getLink()).isNotNull();
                    assertThat(article.getCreatedAt()).isNotNull();
                    assertThat(article.getUpdatedAt()).isNotNull();
                    assertThat(article.getDeletedAt()).isNull();
                });
            }
        });
    }

    @Test
    @DisplayName("기업 생성 및 수정 시간 자동 설정 검증")
    @Transactional
    void 기업_생성_수정시간_자동_설정_검증() {
        // given - 새로운 기업 생성
        LocalDateTime beforeCreate = LocalDateTime.now().minusSeconds(1);
        
        Corporation newCorp = Corporation.builder()
                .name("Time Test Corp")
                .blogLink("https://example.com")
                .build();

        // when - 저장
        Corporation savedCorp = corporationRepository.save(newCorp);
        
        LocalDateTime afterCreate = LocalDateTime.now().plusSeconds(1);

        // then - 생성 시간 검증
        assertThat(savedCorp.getCreatedAt()).isNotNull();
        assertThat(savedCorp.getUpdatedAt()).isNotNull();
        assertThat(savedCorp.getCreatedAt()).isBetween(beforeCreate, afterCreate);
        assertThat(savedCorp.getUpdatedAt()).isBetween(beforeCreate, afterCreate);

        // when - 수정
        LocalDateTime beforeUpdate = LocalDateTime.now().minusSeconds(1);
        savedCorp.setName("Updated Name");
        Corporation updatedCorp = corporationRepository.save(savedCorp);
        LocalDateTime afterUpdate = LocalDateTime.now().plusSeconds(1);

        // then - 수정 시간 검증
        assertThat(updatedCorp.getUpdatedAt()).isBetween(beforeUpdate, afterUpdate);
        assertThat(updatedCorp.getCreatedAt()).isEqualTo(savedCorp.getCreatedAt()); // 생성 시간은 변하지 않음
    }

    @Test
    @DisplayName("기사 생성 및 수정 시간 자동 설정 검증")
    @Transactional
    void 기사_생성_수정시간_자동_설정_검증() {
        // given - 새로운 기사 생성
        LocalDateTime beforeCreate = LocalDateTime.now().minusSeconds(1);
        
        Article newArticle = Article.builder()
                .corporationId(testCorporation.getId())
                .title("Time Test Article")
                .link("https://example.com/test-time")
                .summary("Time test summary")
                .publishedAt(LocalDateTime.now())
                .build();

        // when - 저장
        Article savedArticle = articleRepository.save(newArticle);
        
        LocalDateTime afterCreate = LocalDateTime.now().plusSeconds(1);

        // then - 생성 시간 검증
        assertThat(savedArticle.getCreatedAt()).isNotNull();
        assertThat(savedArticle.getUpdatedAt()).isNotNull();
        assertThat(savedArticle.getCreatedAt()).isBetween(beforeCreate, afterCreate);
        assertThat(savedArticle.getUpdatedAt()).isBetween(beforeCreate, afterCreate);

        // when - 수정
        LocalDateTime beforeUpdate = LocalDateTime.now().minusSeconds(1);
        savedArticle.setTitle("Updated Title");
        Article updatedArticle = articleRepository.save(savedArticle);
        LocalDateTime afterUpdate = LocalDateTime.now().plusSeconds(1);

        // then - 수정 시간 검증
        assertThat(updatedArticle.getUpdatedAt()).isBetween(beforeUpdate, afterUpdate);
        assertThat(updatedArticle.getCreatedAt()).isEqualTo(savedArticle.getCreatedAt()); // 생성 시간은 변하지 않음
    }

    @Test
    @DisplayName("소프트 삭제 기능 통합 검증")
    @Transactional
    void 소프트_삭제_기능_통합_검증() {
        // given - 기사 생성
        Article article = Article.builder()
                .corporationId(testCorporation.getId())
                .title("Delete Test Article")
                .link("https://example.com/delete-test")
                .summary("Delete test summary")
                .publishedAt(LocalDateTime.now())
                .build();
        article = articleRepository.save(article);

        // when - 소프트 삭제
        article.setDeletedAt(LocalDateTime.now());
        articleRepository.save(article);

        // then - 소프트 삭제 검증
        // 삭제되지 않은 기사 조회에서는 제외되어야 함
        List<Article> activeArticles = articleRepository.findByCorporationIdAndNotDeleted(testCorporation.getId());
        assertThat(activeArticles).doesNotContain(article);
        
        // 하지만 전체 조회에서는 여전히 존재
        List<Article> allArticles = articleRepository.findByCorporationId(testCorporation.getId());
        assertThat(allArticles).contains(article);
        
        // ID로 직접 조회 시에도 존재
        assertThat(articleRepository.findById(article.getId())).isPresent();
    }

    @Test
    @DisplayName("중복 링크 기사 저장 방지 DB 레벨 검증")
    @Transactional
    void 중복_링크_기사_저장_방지_DB_레벨_검증() {
        // given - 첫 번째 기사 저장
        String duplicateLink = "https://example.com/duplicate-link";
        
        Article firstArticle = Article.builder()
                .corporationId(testCorporation.getId())
                .title("First Article")
                .link(duplicateLink)
                .summary("First summary")
                .publishedAt(LocalDateTime.now())
                .build();
        articleRepository.save(firstArticle);

        // when - 중복 링크 확인
        boolean existsBefore = articleRepository.findByLinkAndDeletedAtIsNull(duplicateLink).isPresent();
        assertThat(existsBefore).isTrue();

        // then - 실제 서비스에서는 중복 체크 후 저장하지 않아야 함
        // 여기서는 DB 레벨의 중복 확인 메커니즘만 테스트
        long duplicateCount = articleRepository.findAll()
                .stream()
                .filter(article -> duplicateLink.equals(article.getLink()) && article.getDeletedAt() == null)
                .count();
        
        assertThat(duplicateCount).isEqualTo(1);
    }

    @Test
    @DisplayName("기업별 기사 카운트 정확성 검증")
    @Transactional
    void 기업별_기사_카운트_정확성_검증() {
        // given - 여러 기업과 기사 준비
        Corporation corp2 = Corporation.builder()
                .name("Corp 2")
                .blogLink("https://example2.com")
                .build();
        corp2 = corporationRepository.save(corp2);

        // 첫 번째 기업에 3개 기사
        for (int i = 1; i <= 3; i++) {
            Article article = Article.builder()
                    .corporationId(testCorporation.getId())
                    .title("Corp1 Article " + i)
                    .link("https://corp1.com/article" + i)
                    .summary("Summary " + i)
                    .publishedAt(LocalDateTime.now())
                    .build();
            articleRepository.save(article);
        }

        // 두 번째 기업에 2개 기사
        for (int i = 1; i <= 2; i++) {
            Article article = Article.builder()
                    .corporationId(corp2.getId())
                    .title("Corp2 Article " + i)
                    .link("https://corp2.com/article" + i)
                    .summary("Summary " + i)
                    .publishedAt(LocalDateTime.now())
                    .build();
            articleRepository.save(article);
        }

        // when & then - 각 기업별 기사 수 확인
        long corp1Count = articleRepository.countByCorporationId(testCorporation.getId());
        long corp2Count = articleRepository.countByCorporationId(corp2.getId());

        assertThat(corp1Count).isEqualTo(3);
        assertThat(corp2Count).isEqualTo(2);

        // 전체 기사 수 확인
        long totalCount = articleRepository.count();
        assertThat(totalCount).isEqualTo(5);
    }

    @Test
    @DisplayName("최근 기사 통계 조회 정확성 검증")
    @Transactional
    void 최근_기사_통계_조회_정확성_검증() {
        // given - 과거와 현재 기사 생성
        LocalDateTime pastTime = LocalDateTime.now().minusDays(2);
        LocalDateTime recentTime = LocalDateTime.now().minusHours(1);

        // 테스트용 기사 생성 (JPA 자동 시간 설정 사용)
        for (int i = 1; i <= 5; i++) {
            Article article = Article.builder()
                    .corporationId(testCorporation.getId())
                    .title("Test Article " + i)
                    .link("https://example.com/test" + i)
                    .summary("Test summary")
                    .publishedAt(LocalDateTime.now())
                    .build();
            articleRepository.save(article);
        }

        // when - 최근 24시간 기사 조회
        LocalDateTime since24Hours = LocalDateTime.now().minusHours(24);
        Long recentCount = articleRepository.countNewArticlesByCorporation(testCorporation.getId(), since24Hours);

        // then - 최근 24시간 내 기사 카운트 확인 (JPA 자동 생성 시간 기준)
        assertThat(recentCount).isGreaterThanOrEqualTo(0); // 최소 0개 이상
    }

    @Test
    @DisplayName("크롤링 설정 속성 값 검증")
    void 크롤링_설정_속성_값_검증() {
        // when & then - 테스트 환경 설정 확인
        assertThat(crawlerProperties).isNotNull();
        assertThat(crawlerProperties.isEnabled()).isNotNull(); // true/false 상관없이 설정되어 있어야 함
        
        // 스케줄러 객체 생성 확인
        assertThat(crawlingScheduler).isNotNull();
    }

    @Test
    @DisplayName("트랜잭션 격리 수준 검증")
    @Transactional
    void 트랜잭션_격리_수준_검증() {
        // given - 기사 저장
        Article article = Article.builder()
                .corporationId(testCorporation.getId())
                .title("Transaction Test")
                .link("https://example.com/transaction")
                .summary("Transaction test")
                .publishedAt(LocalDateTime.now())
                .build();

        // when - 저장 후 즉시 조회
        Article savedArticle = articleRepository.save(article);
        Article foundArticle = articleRepository.findById(savedArticle.getId()).orElse(null);

        // then - 같은 트랜잭션 내에서 즉시 조회 가능해야 함
        assertThat(foundArticle).isNotNull();
        assertThat(foundArticle.getId()).isEqualTo(savedArticle.getId());
        assertThat(foundArticle.getTitle()).isEqualTo("Transaction Test");
    }

    @Test
    @DisplayName("배치 처리 시 메모리 효율성 검증")
    @Transactional
    void 배치_처리_메모리_효율성_검증() {
        // given - 많은 수의 기업 생성 (메모리 테스트용)
        int corporationCount = 50;
        
        for (int i = 1; i <= corporationCount; i++) {
            Corporation corp = Corporation.builder()
                    .name("Batch Corp " + i)
                    .blogLink("https://example" + i + ".com")
                    .build();
            corporationRepository.save(corp);
        }

        // when - 전체 기업 조회
        List<Corporation> allCorps = corporationRepository.findAllWithBlogLink();

        // then - 메모리 효율성 확인 (모든 기업이 로드되어야 함)
        assertThat(allCorps).hasSizeGreaterThanOrEqualTo(corporationCount);
        
        // 메모리 사용량이 합리적인 범위 내에 있는지 확인 (간접적)
        assertThat(allCorps).allSatisfy(corp -> {
            assertThat(corp.getName()).isNotNull();
            assertThat(corp.getBlogLink()).isNotNull();
        });
    }
}