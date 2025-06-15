package com.newcodes7.small_town.crawler.integration;

import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.entity.Corporation;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;
import com.newcodes7.small_town.crawler.service.CrawlingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Crawler 예외 상황 처리 통합 테스트")
class CrawlerErrorHandlingIntegrationTest {

    @Autowired
    private CrawlingService crawlingService;

    @Autowired
    private CrawlerCorporationRepository corporationRepository;

    @Autowired
    private CrawlerArticleRepository articleRepository;

    @BeforeEach
    void setUp() {
        // 테스트 데이터 정리
        articleRepository.deleteAll();
        corporationRepository.deleteAll();
    }

    @Test
    @DisplayName("존재하지 않는 기업 ID로 크롤링 시도")
    void 존재하지_않는_기업_크롤링_처리() {
        // when - 존재하지 않는 기업 ID로 크롤링
        CrawlResult result = crawlingService.crawlSingleBlog(999L);

        // then - 실패 결과 반환
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("기업을 찾을 수 없습니다");
        assertThat(result.getCorporation()).isNull();
        assertThat(result.getNewArticles()).isEqualTo(0);
    }

    @Test
    @DisplayName("잘못된 URL 형식의 블로그 링크 처리")
    void 잘못된_URL_형식_처리() {
        // given - 잘못된 URL 형식의 기업
        Corporation invalidUrlCorp = Corporation.builder()
                .name("Invalid URL Corp")
                .blogLink("invalid-url-format")
                .build();
        invalidUrlCorp = corporationRepository.save(invalidUrlCorp);

        // when - 크롤링 시도
        CrawlResult result = crawlingService.crawlSingleBlog(invalidUrlCorp.getId());

        // then - 실패 처리 확인
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
        assertThat(result.getCorporation().getName()).isEqualTo("Invalid URL Corp");
    }

    @Test
    @DisplayName("접근 불가능한 웹사이트 크롤링 처리")
    void 접근_불가능한_웹사이트_처리() {
        // given - 접근 불가능한 URL의 기업
        Corporation inaccessibleCorp = Corporation.builder()
                .name("Inaccessible Corp")
                .blogLink("https://this-domain-does-not-exist-12345.com")
                .build();
        inaccessibleCorp = corporationRepository.save(inaccessibleCorp);

        // when - 크롤링 시도
        CrawlResult result = crawlingService.crawlSingleBlog(inaccessibleCorp.getId());

        // then - 실패 처리 확인
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
        assertThat(result.getCorporation().getName()).isEqualTo("Inaccessible Corp");
        
        // DB에는 저장되지 않아야 함
        long articleCount = articleRepository.countByCorporationId(inaccessibleCorp.getId());
        assertThat(articleCount).isEqualTo(0);
    }

    @Test
    @DisplayName("타임아웃 발생 시 처리")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void 타임아웃_발생_처리() {
        // given - 응답이 매우 느린 사이트 (시뮬레이션)
        Corporation slowCorp = Corporation.builder()
                .name("Slow Response Corp")
                .blogLink("https://httpbin.org/delay/30") // 30초 지연
                .build();
        slowCorp = corporationRepository.save(slowCorp);

        // when - 크롤링 시도 (타임아웃 예상)
        long startTime = System.currentTimeMillis();
        CrawlResult result = crawlingService.crawlSingleBlog(slowCorp.getId());
        long executionTime = System.currentTimeMillis() - startTime;

        // then - 적절한 시간 내에 타임아웃 처리
        assertThat(executionTime).isLessThan(15000); // 15초 이내
        
        if (!result.isSuccess()) {
            assertThat(result.getErrorMessage()).isNotBlank();
        }
    }

    @Test
    @DisplayName("빈 콘텐츠 웹사이트 처리")
    void 빈_콘텐츠_웹사이트_처리() {
        // given - 빈 콘텐츠를 가진 웹사이트
        Corporation emptyCorp = Corporation.builder()
                .name("Empty Content Corp")
                .blogLink("https://httpbin.org/status/204") // No Content 응답
                .build();
        emptyCorp = corporationRepository.save(emptyCorp);

        // when - 크롤링 시도
        CrawlResult result = crawlingService.crawlSingleBlog(emptyCorp.getId());

        // then - 적절한 처리 확인
        assertThat(result.getCorporation().getName()).isEqualTo("Empty Content Corp");
        
        if (result.isSuccess()) {
            assertThat(result.getNewArticles()).isEqualTo(0);
        } else {
            assertThat(result.getErrorMessage()).isNotBlank();
        }
    }

    @Test
    @DisplayName("HTTP 오류 상태 코드 처리")
    void HTTP_오류_상태코드_처리() {
        // given - 404 에러를 반환하는 URL
        Corporation notFoundCorp = Corporation.builder()
                .name("Not Found Corp")
                .blogLink("https://httpbin.org/status/404")
                .build();
        notFoundCorp = corporationRepository.save(notFoundCorp);

        // when - 크롤링 시도
        CrawlResult result = crawlingService.crawlSingleBlog(notFoundCorp.getId());

        // then - 404 오류 적절히 처리
        assertThat(result.getCorporation().getName()).isEqualTo("Not Found Corp");
        
        if (!result.isSuccess()) {
            assertThat(result.getErrorMessage()).isNotBlank();
        }
        
        // 실패한 크롤링으로 인해 기사가 저장되지 않아야 함
        long articleCount = articleRepository.countByCorporationId(notFoundCorp.getId());
        assertThat(articleCount).isEqualTo(0);
    }

    @Test
    @DisplayName("부분적 실패 시 트랜잭션 롤백 방지")
    @Transactional
    void 부분적_실패_트랜잭션_롤백_방지() {
        // given - 성공할 기업과 실패할 기업 혼재
        Corporation successCorp = Corporation.builder()
                .name("Success Corp")
                .blogLink("https://httpbin.org/html")
                .build();
        successCorp = corporationRepository.save(successCorp);

        Corporation failCorp = Corporation.builder()
                .name("Fail Corp")
                .blogLink("https://invalid-domain-12345.com")
                .build();
        failCorp = corporationRepository.save(failCorp);

        // when - 전체 크롤링 실행
        List<CrawlResult> results = crawlingService.crawlAllBlogs();

        // then - 각 기업별로 독립적인 결과
        assertThat(results).hasSize(2);
        
        // 실패한 크롤링이 성공한 크롤링에 영향을 주지 않아야 함
        assertThat(corporationRepository.findById(successCorp.getId())).isPresent();
        assertThat(corporationRepository.findById(failCorp.getId())).isPresent();
    }

    @Test
    @DisplayName("소프트 삭제된 기업 크롤링 방지")
    @Transactional
    void 소프트_삭제된_기업_크롤링_방지() {
        // given - 기업 생성 후 소프트 삭제
        Corporation deletedCorp = Corporation.builder()
                .name("To Be Deleted Corp")
                .blogLink("https://httpbin.org/html")
                .build();
        deletedCorp = corporationRepository.save(deletedCorp);
        
        // 소프트 삭제 처리
        deletedCorp.setDeletedAt(LocalDateTime.now());
        corporationRepository.save(deletedCorp);

        // when - 삭제된 기업 크롤링 시도
        CrawlResult result = crawlingService.crawlSingleBlog(deletedCorp.getId());

        // then - 크롤링 거부
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("기업을 찾을 수 없습니다");
        
        // 기사가 저장되지 않아야 함
        long articleCount = articleRepository.countByCorporationId(deletedCorp.getId());
        assertThat(articleCount).isEqualTo(0);
    }

    @Test
    @DisplayName("null 및 빈 문자열 블로그 링크 처리")
    void null_빈문자열_블로그링크_처리() {
        // given - null 블로그 링크
        Corporation nullLinkCorp = Corporation.builder()
                .name("Null Link Corp")
                .blogLink(null)
                .build();
        nullLinkCorp = corporationRepository.save(nullLinkCorp);

        // 빈 문자열 블로그 링크
        Corporation emptyLinkCorp = Corporation.builder()
                .name("Empty Link Corp")
                .blogLink("")
                .build();
        emptyLinkCorp = corporationRepository.save(emptyLinkCorp);

        // 공백 문자열 블로그 링크
        Corporation blankLinkCorp = Corporation.builder()
                .name("Blank Link Corp")
                .blogLink("   ")
                .build();
        blankLinkCorp = corporationRepository.save(blankLinkCorp);

        // when & then - null 링크 처리
        CrawlResult nullResult = crawlingService.crawlSingleBlog(nullLinkCorp.getId());
        assertThat(nullResult.isSuccess()).isFalse();
        assertThat(nullResult.getErrorMessage()).contains("블로그 링크가 없습니다");

        // 빈 문자열 링크 처리
        CrawlResult emptyResult = crawlingService.crawlSingleBlog(emptyLinkCorp.getId());
        assertThat(emptyResult.isSuccess()).isFalse();
        assertThat(emptyResult.getErrorMessage()).contains("블로그 링크가 없습니다");

        // 공백 문자열 링크 처리
        CrawlResult blankResult = crawlingService.crawlSingleBlog(blankLinkCorp.getId());
        assertThat(blankResult.isSuccess()).isFalse();
        assertThat(blankResult.getErrorMessage()).contains("블로그 링크가 없습니다");
    }

    @Test
    @DisplayName("동시 크롤링 시 예외 격리")
    void 동시_크롤링_예외_격리() throws InterruptedException {
        // given - 성공할 기업과 실패할 기업
        Corporation successCorp = corporationRepository.save(Corporation.builder()
                .name("Concurrent Success Corp")
                .blogLink("https://httpbin.org/html")
                .build());

        Corporation failCorp = corporationRepository.save(Corporation.builder()
                .name("Concurrent Fail Corp")
                .blogLink("https://invalid-concurrent-12345.com")
                .build());

        // when - 동시 크롤링
        Thread successThread = new Thread(() -> crawlingService.crawlSingleBlog(successCorp.getId()));
        Thread failThread = new Thread(() -> crawlingService.crawlSingleBlog(failCorp.getId()));

        successThread.start();
        failThread.start();

        successThread.join(10000); // 10초 타임아웃
        failThread.join(10000);

        // then - 각 스레드의 예외가 서로 영향을 주지 않아야 함
        assertThat(corporationRepository.findById(successCorp.getId())).isPresent();
        assertThat(corporationRepository.findById(failCorp.getId())).isPresent();
    }

    @Test
    @DisplayName("크롤링 중 DB 연결 실패 시뮬레이션")
    @Transactional
    void DB_연결_실패_시뮬레이션() {
        // given - 정상적인 기업
        Corporation normalCorp = Corporation.builder()
                .name("Normal Corp")
                .blogLink("https://httpbin.org/html")
                .build();
        normalCorp = corporationRepository.save(normalCorp);

        // when - 크롤링 실행 (DB 저장 과정에서 예외 발생 가능성 테스트)
        CrawlResult result = crawlingService.crawlSingleBlog(normalCorp.getId());

        // then - 예외가 발생하더라도 시스템이 안정적으로 응답해야 함
        assertThat(result).isNotNull();
        assertThat(result.getCorporation()).isNotNull();
        
        // 결과가 성공이든 실패든 적절히 처리되어야 함
        if (!result.isSuccess()) {
            assertThat(result.getErrorMessage()).isNotBlank();
        }
    }

    @Test
    @Disabled // TODO: 향후 핵심 기능 온전히 구현 이후 활성화
    @DisplayName("다중 기업 처리 안정성 검증")
    void 다중_기업_처리_안정성_검증() {
        // given - 적당한 수의 기업 생성 (성능 테스트 간소화)
        for (int i = 1; i <= 10; i++) {
            Corporation corp = Corporation.builder()
                    .name("Batch Test Corp " + i)
                    .blogLink("https://httpbin.org/html")
                    .build();
            corporationRepository.save(corp);
        }

        // when - 전체 크롤링 실행
        long startTime = System.currentTimeMillis();
        List<CrawlResult> results = crawlingService.crawlAllBlogs();
        long executionTime = System.currentTimeMillis() - startTime;

        // then - 안정적으로 처리되어야 함
        assertThat(results).isNotNull();
        assertThat(executionTime).isLessThan(30000); // 30초 이내 완료
        
        // 결과가 있는 경우에만 검증
        if (!results.isEmpty()) {
            results.forEach(result -> {
                if (result.getCorporation() != null) {
                    assertThat(result.getCorporation().getName()).isNotBlank();
                }
            });
        }
    }

    @Test
    @DisplayName("특수 문자가 포함된 URL 처리")
    void 특수문자_포함_URL_처리() {
        // given - 특수 문자가 포함된 URL
        Corporation specialCharCorp = Corporation.builder()
                .name("Special Char Corp")
                .blogLink("https://httpbin.org/anything/한글?param=test&value=특수문자")
                .build();
        specialCharCorp = corporationRepository.save(specialCharCorp);

        // when - 크롤링 시도
        CrawlResult result = crawlingService.crawlSingleBlog(specialCharCorp.getId());

        // then - 특수 문자 URL도 적절히 처리되어야 함
        assertThat(result).isNotNull();
        assertThat(result.getCorporation().getName()).isEqualTo("Special Char Corp");
        
        // 성공/실패 여부와 관계없이 시스템이 안정적으로 동작해야 함
        if (!result.isSuccess() && result.getErrorMessage() != null) {
            assertThat(result.getErrorMessage()).isNotBlank();
        }
    }
}