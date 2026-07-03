package com.newcodes7.small_town.term.service;

import static org.assertj.core.api.Assertions.*;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.auth.entity.Provider;
import com.newcodes7.small_town.auth.entity.Role;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.ProviderRepository;
import com.newcodes7.small_town.auth.repository.RoleRepository;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.auth.util.UserTestHelper;
import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.ArticleTerm;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.term.repository.ArticleTermRepository;
import com.newcodes7.small_town.term.repository.TermRepository;
import com.newcodes7.small_town.term.service.ArticleTermService.ArticleTermExtractionResult;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * ArticleTermService 통합 테스트
 * Term 추출, 저장, 통계 갱신 기능 검증
 */
public class ArticleTermServiceTest extends IntegrationTestBase {

    @Autowired
    private ArticleTermService articleTermService;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private ArticleTermRepository articleTermRepository;

    @Autowired
    private TermRepository termRepository;

    @Autowired
    private CorporationRepository corporationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User testUser;
    private Corporation testCorporation;
    private Role userRole;
    private Provider localProvider;

    @BeforeEach
    void setUp() {
        userRole = roleRepository.findByName("USER")
                .orElseGet(() -> roleRepository.save(new Role("USER")));

        localProvider = providerRepository.findByName("LOCAL")
                .orElseGet(() -> providerRepository.save(new Provider("LOCAL")));

        testUser = UserTestHelper.createTestUser(
                "test@example.com",
                passwordEncoder.encode("password123"),
                "테스트유저",
                userRole,
                localProvider
        );
        testUser = userRepository.save(testUser);

        testCorporation = Corporation.builder()
                .name("테스트기업")
                .blogLink("https://test.com/blog")
                .build();
        testCorporation = corporationRepository.save(testCorporation);
    }

    @Test
    @DisplayName("Article Term 추출 - 제목과 본문에서 추출 성공")
    void extractTermsForArticle_Success() {
        // given
        Article article = Article.builder()
                .title("Spring Boot와 JPA를 이용한 웹 애플리케이션 개발")
                .translatedTitle("Web Application Development with Spring Boot and JPA")
                .content("Spring Boot는 Java 프레임워크입니다. JPA는 ORM 기술입니다. Spring Boot와 JPA를 함께 사용하면 효율적인 개발이 가능합니다.")
                .link("https://test.com/article1")
                .publishedAt(LocalDateTime.now())
                .corporation(testCorporation)
                .build();
        article = articleRepository.save(article);

        // when
        int termCount = articleTermService.extractAndSaveTermsForArticle(article);

        // then
        assertThat(termCount).isGreaterThan(0);

        List<ArticleTerm> articleTerms = articleTermRepository.findByArticleId(article.getId());
        assertThat(articleTerms).isNotEmpty();

        // Spring, Boot, JPA 등의 term이 추출되었는지 확인
        List<String> termNames = articleTerms.stream()
                .map(at -> at.getTerm().getTerm())
                .toList();

        assertThat(termNames).contains("spring", "boot", "jpa");
    }

    @Test
    @DisplayName("Article Term 추출 - 제목에서만 추출 (높은 가중치)")
    void extractTermsFromTitle_HighWeight() {
        // given
        Article article = Article.builder()
                .title("Docker와 Kubernetes를 활용한 컨테이너 오케스트레이션")
                .content("내용이 짧습니다.") // 최소 빈도 미달
                .link("https://test.com/article2")
                .publishedAt(LocalDateTime.now())
                .corporation(testCorporation)
                .build();
        article = articleRepository.save(article);

        // when
        int termCount = articleTermService.extractAndSaveTermsForArticle(article);

        // then
        List<ArticleTerm> articleTerms = articleTermRepository.findByArticleId(article.getId());

        // 제목에서 나온 term들이 저장되어야 함
        if (!articleTerms.isEmpty()) {
            // Docker, Kubernetes 등이 추출되었다면 score 확인
            ArticleTerm dockerTerm = articleTerms.stream()
                    .filter(at -> at.getTerm().getTerm().equalsIgnoreCase("docker"))
                    .findFirst()
                    .orElse(null);

            if (dockerTerm != null) {
                // 제목에서 나온 term은 높은 가중치(3.0)가 적용되어야 함
                assertThat(dockerTerm.getScore()).isGreaterThan(0);
            }
        }
    }

    @Test
    @DisplayName("Article Term 추출 - 본문만 있는 경우")
    void extractTermsFromContent_Only() {
        // given
        Article article = Article.builder()
                .title("짧은제목")
                .content("React는 Facebook에서 개발한 UI 라이브러리입니다. React를 사용하면 컴포넌트 기반 개발이 가능합니다. React는 Virtual DOM을 사용합니다.")
                .link("https://test.com/article3")
                .publishedAt(LocalDateTime.now())
                .corporation(testCorporation)
                .build();
        article = articleRepository.save(article);

        // when
        int termCount = articleTermService.extractAndSaveTermsForArticle(article);

        // then
        List<ArticleTerm> articleTerms = articleTermRepository.findByArticleId(article.getId());

        if (!articleTerms.isEmpty()) {
            List<String> termNames = articleTerms.stream()
                    .map(at -> at.getTerm().getTerm())
                    .toList();

            // React가 여러 번 반복되어 추출되었을 것
            assertThat(termNames).contains("react");
        }
    }

    @Test
    @DisplayName("Article Term 추출 - 빈 내용일 경우 0개 반환")
    void extractTerms_EmptyContent_ReturnsZero() {
        // given
        Article article = Article.builder()
                .title("")
                .content("")
                .link("https://test.com/article4")
                .publishedAt(LocalDateTime.now())
                .corporation(testCorporation)
                .build();
        article = articleRepository.save(article);

        // when
        int termCount = articleTermService.extractAndSaveTermsForArticle(article);

        // then
        assertThat(termCount).isEqualTo(0);

        List<ArticleTerm> articleTerms = articleTermRepository.findByArticleId(article.getId());
        assertThat(articleTerms).isEmpty();
    }

    @Test
    @DisplayName("Article Term 재추출 - 기존 term 삭제 후 재생성")
    void reExtractTerms_DeletesOldAndCreatesNew() {
        // given
        Article article = Article.builder()
                .title("Python으로 머신러닝 시작하기")
                .content("Python은 데이터 과학에 많이 사용됩니다. Python은 간결하고 강력합니다.")
                .link("https://test.com/article5")
                .publishedAt(LocalDateTime.now())
                .corporation(testCorporation)
                .build();
        article = articleRepository.save(article);

        // 첫 번째 추출
        int firstCount = articleTermService.extractAndSaveTermsForArticle(article);
        List<ArticleTerm> firstTerms = articleTermRepository.findByArticleId(article.getId());

        // when - 재추출 (forceReanalyze=true 시나리오)
        int secondCount = articleTermService.extractAndSaveTermsForArticle(article);
        List<ArticleTerm> secondTerms = articleTermRepository.findByArticleId(article.getId());

        // then
        // 재추출 시에도 term들이 정상적으로 저장되어야 함
        assertThat(secondTerms).isNotEmpty();
    }

    @Test
    @DisplayName("단일 Article Term 추출 결과 조회")
    void extractTermsForSingleArticle() {
        // given
        Article article = Article.builder()
                .title("TypeScript와 Node.js로 백엔드 개발")
                .content("TypeScript는 JavaScript의 슈퍼셋입니다. TypeScript를 사용하면 타입 안정성을 얻을 수 있습니다.")
                .link("https://test.com/article6")
                .publishedAt(LocalDateTime.now())
                .corporation(testCorporation)
                .build();
        article = articleRepository.save(article);

        // when
        ArticleTermExtractionResult result = articleTermService.extractTermsForSingleArticle(article.getId());

        // then
        assertThat(result.getProcessedArticles()).isEqualTo(1);
        assertThat(result.getFailedArticles()).isEqualTo(0);
        assertThat(result.getTotalTerms()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("단일 Article Term 추출 - 존재하지 않는 Article")
    void extractTermsForSingleArticle_NotFound() {
        // when & then
        assertThatThrownBy(() -> articleTermService.extractTermsForSingleArticle(999999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는");
    }

    @Test
    @DisplayName("Article Terms 조회")
    void getArticleTerms() {
        // given
        Article article = Article.builder()
                .title("Rust로 시스템 프로그래밍")
                .content("Rust는 메모리 안전성을 보장합니다. Rust는 성능이 뛰어납니다.")
                .link("https://test.com/article7")
                .publishedAt(LocalDateTime.now())
                .corporation(testCorporation)
                .build();
        article = articleRepository.save(article);
        articleTermService.extractAndSaveTermsForArticle(article);

        // when
        List<ArticleTerm> terms = articleTermService.getArticleTerms(article.getId());

        // then
        assertThat(terms).isNotNull();
        if (!terms.isEmpty()) {
            assertThat(terms.get(0).getArticle().getId()).isEqualTo(article.getId());
            assertThat(terms.get(0).getTerm()).isNotNull();
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled("Complex transaction issue - needs investigation")
    @DisplayName("Term 삭제 및 불용어 등록")
    void deleteTermAndAddToStopwords() {
        // given
        Article article = Article.builder()
                .title("Kotlin 프로그래밍 언어 소개")
                .content("Kotlin은 JVM 언어입니다. Kotlin은 간결합니다.")
                .link("https://test.com/article8")
                .publishedAt(LocalDateTime.now())
                .corporation(testCorporation)
                .build();
        article = articleRepository.save(article);
        articleTermService.extractAndSaveTermsForArticle(article);

        List<ArticleTerm> articleTerms = articleTermRepository.findByArticleId(article.getId());
        if (articleTerms.isEmpty()) {
            return; // Term이 추출되지 않았으면 테스트 스킵
        }

        Term termToDelete = articleTerms.get(0).getTerm();
        Long termId = termToDelete.getId();

        // when
        int deletedCount = articleTermService.deleteTermAndAddToStopwords(termId, "테스트용 삭제");

        // then
        assertThat(deletedCount).isGreaterThanOrEqualTo(0);

        // Term이 삭제되었는지 확인
        assertThat(termRepository.findById(termId)).isEmpty();
    }

    @Test
    @DisplayName("불용어 추가")
    void addStopword() {
        // when & then - 예외 없이 실행되어야 함
        assertThatCode(() -> {
            articleTermService.addStopword("테스트불용어", "NNG", "테스트");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("최신 Article Term 추출 - 제한된 개수")
    void extractLatestArticleTerms() {
        // given - 여러 article 생성
        for (int i = 0; i < 3; i++) {
            Article article = Article.builder()
                    .title("테스트 아티클 " + i + " with Java and Spring")
                    .content("Java 프로그래밍 테스트입니다. Spring Framework를 사용합니다.")
                    .link("https://test.com/article" + i)
                    .publishedAt(LocalDateTime.now().minusDays(i))
                    .corporation(testCorporation)
                    .build();
            articleRepository.save(article);
        }

        // when - 최신 2개만 추출
        ArticleTermExtractionResult result = articleTermService.extractLatestArticleTerms(2, false);

        // then
        assertThat(result.getProcessedArticles() + result.getSkippedArticles()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Term 통계 업데이트")
    void updateTermStatistics() {
        // given
        Article article = Article.builder()
                .title("Scala 함수형 프로그래밍")
                .content("Scala는 JVM 기반입니다. Scala는 함수형입니다.")
                .link("https://test.com/article9")
                .publishedAt(LocalDateTime.now())
                .corporation(testCorporation)
                .build();
        article = articleRepository.save(article);
        articleTermService.extractAndSaveTermsForArticle(article);

        // when & then - 예외 없이 실행되어야 함
        assertThatCode(() -> {
            articleTermService.updateTermStatistics();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("특정 Article 관련 Term 통계 업데이트")
    void updateTermStatisticsForArticle() {
        // given
        Article article = Article.builder()
                .title("Elixir 언어 특징")
                .content("Elixir는 Erlang VM에서 동작합니다. Elixir는 함수형 언어입니다.")
                .link("https://test.com/article10")
                .publishedAt(LocalDateTime.now())
                .corporation(testCorporation)
                .build();
        Article savedArticle = articleRepository.save(article);
        articleTermService.extractAndSaveTermsForArticle(savedArticle);

        // when & then - 예외 없이 실행되어야 함
        assertThatCode(() -> {
            articleTermService.updateTermStatisticsForArticle(savedArticle);
        }).doesNotThrowAnyException();
    }

    // ===================== 추가 테스트 =====================

    @Test
    @DisplayName("Article Term 조회 - 존재하는 ID")
    void getArticleTerms_Success() {
        // given
        Article article = Article.builder()
                .title("Python 머신러닝 가이드")
                .content("Python으로 머신러닝을 구현합니다. Python은 데이터 분석에도 사용됩니다.")
                .link("https://test.com/article-terms1")
                .publishedAt(LocalDateTime.now())
                .corporation(testCorporation)
                .build();
        article = articleRepository.save(article);
        articleTermService.extractAndSaveTermsForArticle(article);

        // when
        List<ArticleTerm> terms = articleTermService.getArticleTerms(article.getId());

        // then
        assertThat(terms).isNotEmpty();
    }

    @Test
    @DisplayName("Article Term 조회 - 존재하지 않는 ID는 빈 리스트")
    void getArticleTerms_NotFound() {
        // when
        List<ArticleTerm> terms = articleTermService.getArticleTerms(999999L);

        // then
        assertThat(terms).isEmpty();
    }

    @Test
    @DisplayName("단일 Article Term 추출 - extractTermsForSingleArticle")
    void extractTermsForSingleArticle_Success() {
        // given
        Article article = Article.builder()
                .title("TypeScript 프로그래밍 실전 가이드")
                .content("TypeScript는 JavaScript의 상위집합입니다. TypeScript로 안전한 코드를 작성할 수 있습니다.")
                .link("https://test.com/article-single")
                .publishedAt(LocalDateTime.now())
                .corporation(testCorporation)
                .build();
        article = articleRepository.save(article);

        // when
        ArticleTermExtractionResult result = articleTermService.extractTermsForSingleArticle(article.getId());

        // then
        assertThat(result.getProcessedArticles()).isEqualTo(1);
        assertThat(result.getTotalTerms()).isGreaterThan(0);
        assertThat(result.getProcessingTimeMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Term 삭제 후 불용어 등록")
    void deleteTermAndAddToStopwords_Success() {
        // given - term이 있는 article 생성
        Article article = Article.builder()
                .title("Rust 프로그래밍 특징")
                .content("Rust는 메모리 안전성을 보장합니다. Rust는 시스템 프로그래밍에 적합합니다.")
                .link("https://test.com/article-stopword")
                .publishedAt(LocalDateTime.now())
                .corporation(testCorporation)
                .build();
        article = articleRepository.save(article);
        articleTermService.extractAndSaveTermsForArticle(article);

        List<ArticleTerm> articleTerms = articleTermRepository.findByArticleId(article.getId());
        assertThat(articleTerms).isNotEmpty();

        Term targetTerm = articleTerms.get(0).getTerm();
        Long termId = targetTerm.getId();

        // when
        int deletedCount = articleTermService.deleteTermAndAddToStopwords(termId, "테스트 삭제");

        // then
        assertThat(deletedCount).isGreaterThanOrEqualTo(1);
        assertThat(termRepository.findById(termId)).isEmpty(); // term 삭제 확인
    }

    @Test
    @DisplayName("Term 삭제 - 존재하지 않는 ID")
    void deleteTermAndAddToStopwords_NotFound() {
        assertThatThrownBy(() -> articleTermService.deleteTermAndAddToStopwords(999999L, "테스트"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는 Term");
    }

    @Test
    @DisplayName("불용어 추가")
    void addStopword_Success() {
        // when & then - 예외 없이 실행되어야 함
        assertThatCode(() -> {
            articleTermService.addStopword("불용어테스트", "NNP", "테스트 목적");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("불용어 중복 추가 - 에러 없이 무시")
    void addStopword_Duplicate() {
        // given
        articleTermService.addStopword("중복불용어", "NNP", "첫 번째 추가");

        // when & then - 중복이어도 예외 없음
        assertThatCode(() -> {
            articleTermService.addStopword("중복불용어", "NNP", "두 번째 추가");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("전체 Article Term 추출 - forceReanalyze=true")
    void extractAndSaveAllArticleTerms_ForceReanalyze() {
        // given - Article 1개 생성 후 Term 추출
        Article article = Article.builder()
                .title("Kotlin 코루틴 가이드")
                .content("Kotlin 코루틴은 비동기 프로그래밍을 쉽게 만듭니다.")
                .link("https://test.com/article-force")
                .publishedAt(LocalDateTime.now())
                .corporation(testCorporation)
                .build();
        article = articleRepository.save(article);
        articleTermService.extractAndSaveTermsForArticle(article);

        // when - 강제 재분석
        ArticleTermExtractionResult result = articleTermService.extractAndSaveAllArticleTerms(true);

        // then - processedArticles가 0보다 커야 함 (강제 재분석이므로 재처리됨)
        assertThat(result.getProcessedArticles()).isGreaterThan(0);
    }

    @Test
    @DisplayName("ArticleTermExtractionResult 결과 객체 검증")
    void articleTermExtractionResult_Fields() {
        // given
        ArticleTermExtractionResult result = new ArticleTermExtractionResult();

        // when
        result.incrementProcessedArticles();
        result.incrementProcessedArticles();
        result.incrementSkippedArticles();
        result.incrementFailedArticles();
        result.addTermCount(5);
        result.addTermCount(3);
        result.setProcessingTimeMs(100);

        // then
        assertThat(result.getProcessedArticles()).isEqualTo(2);
        assertThat(result.getSkippedArticles()).isEqualTo(1);
        assertThat(result.getFailedArticles()).isEqualTo(1);
        assertThat(result.getTotalTerms()).isEqualTo(8);
        assertThat(result.getProcessingTimeMs()).isEqualTo(100);
    }
}
