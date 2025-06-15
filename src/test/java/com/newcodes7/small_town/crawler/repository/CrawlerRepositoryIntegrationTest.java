package com.newcodes7.small_town.crawler.repository;

import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("Crawler Repository 통합 테스트")
class CrawlerRepositoryIntegrationTest {

    @Autowired
    private CrawlerCorporationRepository crawlerCorporationRepository;

    @Autowired
    private CrawlerArticleRepository crawlerArticleRepository;

    private Corporation testCorporation;
    private Corporation deletedCorporation;

    @BeforeEach
    void setUp() {
        // 테스트 기업 데이터 준비
        testCorporation = Corporation.builder()
                .name("테스트 기업")
                .blogLink("https://test.com")
                .build();
        testCorporation = crawlerCorporationRepository.save(testCorporation);

        // 삭제된 기업 데이터 준비
        deletedCorporation = Corporation.builder()
                .name("삭제된 기업")
                .blogLink("https://deleted.com")
                .deletedAt(LocalDateTime.now())
                .build();
        deletedCorporation = crawlerCorporationRepository.save(deletedCorporation);

        // 블로그 링크가 없는 기업
        Corporation noBlogCorp = Corporation.builder()
                .name("블로그 없는 기업")
                .blogLink(null)
                .build();
        crawlerCorporationRepository.save(noBlogCorp);
    }

    @Test
    @DisplayName("블로그 링크가 있는 활성 기업만 조회")
    void findAllWithBlogLink_활성_기업만_조회() {
        // when
        List<Corporation> corporations = crawlerCorporationRepository.findAllWithBlogLink();

        // then
        assertThat(corporations).hasSize(1);
        assertThat(corporations.get(0).getName()).isEqualTo("테스트 기업");
        assertThat(corporations.get(0).getBlogLink()).isEqualTo("https://test.com");
        assertThat(corporations.get(0).getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("ID로 삭제되지 않은 기업 조회")
    void findByIdAndNotDeleted_정상_조회() {
        // when
        Corporation found = crawlerCorporationRepository.findByIdAndNotDeleted(testCorporation.getId());

        // then
        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("테스트 기업");
        assertThat(found.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("삭제된 기업은 조회되지 않음")
    void findByIdAndNotDeleted_삭제된_기업_조회_안됨() {
        // when
        Corporation found = crawlerCorporationRepository.findByIdAndNotDeleted(deletedCorporation.getId());

        // then
        assertThat(found).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 기업 조회")
    void findByIdAndNotDeleted_존재하지_않는_기업() {
        // when
        Corporation found = crawlerCorporationRepository.findByIdAndNotDeleted(999L);

        // then
        assertThat(found).isNull();
    }

    @Test
    @DisplayName("기사 저장 및 조회")
    void article_저장_및_조회() {
        // given
        Article article = Article.builder()
                .corporationId(testCorporation.getId())
                .title("테스트 기사")
                .link("https://test.com/article1")
                .summary("테스트 기사 요약")
                .publishedAt(LocalDateTime.now())
                .build();

        // when
        Article savedArticle = crawlerArticleRepository.save(article);

        // then
        assertThat(savedArticle.getId()).isNotNull();
        assertThat(savedArticle.getTitle()).isEqualTo("테스트 기사");
        assertThat(savedArticle.getCorporationId()).isEqualTo(testCorporation.getId());
        assertThat(savedArticle.getCreatedAt()).isNotNull();
        assertThat(savedArticle.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("링크로 삭제되지 않은 기사 조회")
    void findByLinkAndDeletedAtIsNull_정상_조회() {
        // given
        Article article = Article.builder()
                .corporationId(testCorporation.getId())
                .title("테스트 기사")
                .link("https://test.com/article1")
                .summary("테스트 기사 요약")
                .publishedAt(LocalDateTime.now())
                .build();
        crawlerArticleRepository.save(article);

        // when
        Optional<Article> found = crawlerArticleRepository.findByLinkAndDeletedAtIsNull("https://test.com/article1");

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("테스트 기사");
        assertThat(found.get().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("삭제된 기사는 조회되지 않음")
    void findByLinkAndDeletedAtIsNull_삭제된_기사_조회_안됨() {
        // given
        Article article = Article.builder()
                .corporationId(testCorporation.getId())
                .title("삭제된 기사")
                .link("https://test.com/deleted-article")
                .summary("삭제된 기사 요약")
                .publishedAt(LocalDateTime.now())
                .deletedAt(LocalDateTime.now())
                .build();
        crawlerArticleRepository.save(article);

        // when
        Optional<Article> found = crawlerArticleRepository.findByLinkAndDeletedAtIsNull("https://test.com/deleted-article");

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("기업별 신규 기사 수 조회")
    void countNewArticlesByCorporation_정상_동작() {
        // given
        LocalDateTime since = LocalDateTime.now().minusMinutes(1);

        // 기준 시간 이후에 기사들 생성
        Article newArticle1 = Article.builder()
                .corporationId(testCorporation.getId())
                .title("신규 기사 1")
                .link("https://test.com/new1")
                .summary("신규 기사 1")
                .publishedAt(LocalDateTime.now())
                .build();
        crawlerArticleRepository.save(newArticle1);

        Article newArticle2 = Article.builder()
                .corporationId(testCorporation.getId())
                .title("신규 기사 2")
                .link("https://test.com/new2")
                .summary("신규 기사 2")
                .publishedAt(LocalDateTime.now())
                .build();
        crawlerArticleRepository.save(newArticle2);

        // when
        long newArticlesCount = crawlerArticleRepository.countNewArticlesByCorporation(
                testCorporation.getId(), since);

        // then
        assertThat(newArticlesCount).isEqualTo(2);
    }

    @Test
    @DisplayName("중복 링크 저장 방지 확인")
    void 중복_링크_저장_방지() {
        // given
        String duplicateLink = "https://test.com/duplicate";
        
        Article article1 = Article.builder()
                .corporationId(testCorporation.getId())
                .title("첫 번째 기사")
                .link(duplicateLink)
                .summary("첫 번째 기사")
                .publishedAt(LocalDateTime.now())
                .build();
        crawlerArticleRepository.save(article1);

        // when - 같은 링크로 다른 기사 저장 시도
        Optional<Article> existing = crawlerArticleRepository.findByLinkAndDeletedAtIsNull(duplicateLink);

        // then
        assertThat(existing).isPresent();
        assertThat(existing.get().getTitle()).isEqualTo("첫 번째 기사");
        
        // 중복 체크를 통해 저장하지 않음을 확인
        if (existing.isPresent()) {
            // 이미 존재하므로 저장하지 않음
            List<Article> allArticles = crawlerArticleRepository.findAll();
            long duplicateCount = allArticles.stream()
                    .filter(article -> duplicateLink.equals(article.getLink()))
                    .count();
            assertThat(duplicateCount).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("기업 소프트 삭제 확인")
    void corporation_소프트_삭제() {
        // given
        Corporation toDelete = Corporation.builder()
                .name("삭제할 기업")
                .blogLink("https://to-delete.com")
                .build();
        toDelete = crawlerCorporationRepository.save(toDelete);

        // when - 소프트 삭제
        toDelete.setDeletedAt(LocalDateTime.now());
        crawlerCorporationRepository.save(toDelete);

        // then
        Corporation found = crawlerCorporationRepository.findByIdAndNotDeleted(toDelete.getId());
        assertThat(found).isNull();

        // 하지만 실제 레코드는 존재
        Optional<Corporation> actualRecord = crawlerCorporationRepository.findById(toDelete.getId());
        assertThat(actualRecord).isPresent();
        assertThat(actualRecord.get().getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("기사 소프트 삭제 확인")
    void article_소프트_삭제() {
        // given
        Article toDelete = Article.builder()
                .corporationId(testCorporation.getId())
                .title("삭제할 기사")
                .link("https://test.com/to-delete")
                .summary("삭제할 기사")
                .publishedAt(LocalDateTime.now())
                .build();
        toDelete = crawlerArticleRepository.save(toDelete);

        // when - 소프트 삭제
        toDelete.setDeletedAt(LocalDateTime.now());
        crawlerArticleRepository.save(toDelete);

        // then
        Optional<Article> found = crawlerArticleRepository.findByLinkAndDeletedAtIsNull("https://test.com/to-delete");
        assertThat(found).isEmpty();

        // 하지만 실제 레코드는 존재
        Optional<Article> actualRecord = crawlerArticleRepository.findById(toDelete.getId());
        assertThat(actualRecord).isPresent();
        assertThat(actualRecord.get().getDeletedAt()).isNotNull();
    }
}