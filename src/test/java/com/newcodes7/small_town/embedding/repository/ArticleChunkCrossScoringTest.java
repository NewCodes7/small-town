package com.newcodes7.small_town.embedding.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.embedding.entity.ArticleChunk;
import com.newcodes7.small_town.embedding.entity.ChunkVector;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.utils.ArticleCreator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * cross-scoring 보충 쿼리(computeSimilarityForArticleIds)를 <b>실제 DB에서 실행</b>해 검증한다.
 *
 * <p>이 쿼리는 검색 1건당 DB 예산의 33%를 쓰는 최대 비용 지점인데, 다른 검색 테스트는
 * Repository를 스텁하므로 한 번도 실행되지 않는다 — native 쿼리는 부팅 시 검증되지 않아
 * 오타나 파라미터 바인딩 실패가 있어도 운영 첫 검색에서야 터진다
 * (docs/operations/PGSS_SEARCH_COST.md 그 외 3번 / 항목 B').
 *
 * <p>픽스처는 ArticleChunkTwoStageSearchTest와 같은 트릭을 쓴다: 질의 벡터를 e1으로 두어
 * 코사인 유사도를 청크 벡터의 첫 성분으로, 질의 binary를 전부 1로 두어 해밍 거리를 0비트 수로
 * 직접 지정한다. <b>유사도와 해밍 거리를 서로 독립적으로 지정할 수 있어</b> "해밍은 가까운데
 * 유사도는 낮은 청크" 같은 조합을 만들 수 있다.
 */
public class ArticleChunkCrossScoringTest extends IntegrationTestBase {

    @Autowired
    private ArticleChunkRepository chunkRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private static final int DIM = 1024;
    private static final int TOP_K = 3;

    /** 질의 벡터 e1 = [1,0,0,...] — 정규화해도 e1이라 유사도가 청크 벡터의 첫 성분과 같아진다 */
    private static final String QUERY_EMBEDDING = queryEmbedding();

    /** 질의 binary는 전부 1 — 청크의 0 비트 수가 곧 해밍 거리라 Stage 1 순위를 정확히 제어할 수 있다 */
    private static final String QUERY_BINARY = "1".repeat(DIM);

    /** Stage 1 하한을 사실상 해제하는 값 (코사인 최솟값) */
    private static final double NO_FLOOR = -1.0;

    /** 해밍이 가깝지만 유사도는 낮은 청크 3개 + 해밍이 먼 최고 청크 1개 */
    private Article nearArticle;
    /** 해밍·유사도 모두 중간 */
    private Article midArticle;
    /** 해밍은 멀지만 유사도는 최고 — binary 근사가 놓치는 케이스 */
    private Article farArticle;
    /** deleted_at이 설정된 아티클 */
    private Article deletedArticle;

    @BeforeEach
    void setUpChunks() {
        Corporation corporation = ArticleCreator.createCorporation(1L);
        entityManager.persist(corporation);

        nearArticle = persistArticle(corporation);
        midArticle = persistArticle(corporation);
        farArticle = persistArticle(corporation);
        deletedArticle = persistArticle(corporation);

        // 해밍 상위 3개가 유사도는 낮고, 진짜 최고 청크는 해밍상 멀리 있다
        persistChunk(nearArticle, 0, 0.30, 0);
        persistChunk(nearArticle, 1, 0.30, 1);
        persistChunk(nearArticle, 2, 0.30, 2);
        persistChunk(nearArticle, 3, 0.95, 500);

        persistChunk(midArticle, 0, 0.60, 200);
        persistChunk(midArticle, 1, 0.60, 200);
        persistChunk(midArticle, 2, 0.60, 200);

        persistChunk(farArticle, 0, 0.95, 500);
        persistChunk(farArticle, 1, 0.95, 500);
        persistChunk(farArticle, 2, 0.95, 500);

        persistChunk(deletedArticle, 0, 0.99, 0);
        persistChunk(deletedArticle, 1, 0.99, 0);
        persistChunk(deletedArticle, 2, 0.99, 0);
        deletedArticle.softDelete();

        entityManager.flush();
    }

    @Test
    @DisplayName("id를 배열 파라미터로 넘겨도 전 청크 중 상위 topK 평균이 계산된다")
    void 배열_파라미터로_상위K_평균이_계산된다() {
        Map<Long, Double> scores = crossScore(allIds());

        // near: 상위 3개는 유사도 기준(0.95, 0.30, 0.30) — 해밍이 먼 청크도 반드시 포함된다
        assertThat(scores.get(nearArticle.getId())).isCloseTo(0.5167, within(0.01));
        assertThat(scores.get(midArticle.getId())).isCloseTo(0.60, within(0.01));
        assertThat(scores.get(farArticle.getId())).isCloseTo(0.95, within(0.01));
    }

    @Test
    @DisplayName("deleted_at 아티클도 이 쿼리들은 반환한다 — 제외는 서비스의 유효성 게이트가 담당")
    void deleted_아티클은_레포지토리에서_걸러지지_않는다() {
        // 2026-08-17: 두 cross-scoring 쿼리에서 article 조인을 제거했다. 그 조인은 청크 행마다
        // article PK를 찍어 요청당 1,113회(블록의 21%, 27MB)를 썼고, HNSW의 ORDER BY ... LIMIT
        // 위에 놓여 후보 수(recall)까지 깎았다.
        // 최종 노출은 ArticleSearchService Phase B의 validArticleIds가 막는다 —
        // ArticleSearchServiceTest의 "필터 있음 + stale article(삭제됨) → 최종 결과에서 제외" 참고.
        // 근거: load-test/results/2026-08-17-osiv-connection-hold-ab.md 6장
        assertThat(crossScore(allIds())).containsKey(deletedArticle.getId());
        assertThat(crossScoreTwoStage(allIds(), NO_FLOOR, 10)).containsKey(deletedArticle.getId());
    }

    @Test
    @DisplayName("요청한 id만 반환된다 (배열 파라미터가 실제로 필터로 동작)")
    void 요청하지_않은_아티클은_반환되지_않는다() {
        Map<Long, Double> scores = crossScore(List.of(midArticle.getId()));

        assertThat(scores).containsOnlyKeys(midArticle.getId());
    }

    // ==================== 2단계(퍼널) 버전 — 항목 B' ====================

    @Test
    @DisplayName("퍼널은 생존 아티클의 점수를 단일 쿼리와 완전히 같은 값으로 반환한다")
    void 퍼널_생존_아티클_점수는_단일쿼리와_동일하다() {
        Map<Long, Double> single = crossScore(allIds());
        Map<Long, Double> funnel = crossScoreTwoStage(allIds(), NO_FLOOR, 10);

        // 컷도 하한도 걸리지 않으면 두 결과가 키·값 모두 같아야 한다.
        // Stage 2가 생존 아티클의 전 청크를 그대로 다시 읽기 때문 — 이게 퍼널의 핵심 불변식이다.
        assertThat(funnel).containsOnlyKeys(single.keySet().toArray(new Long[0]));
        single.forEach((id, score) -> assertThat(funnel.get(id)).isCloseTo(score, within(1e-9)));
    }

    @Test
    @DisplayName("Stage 2도 전 청크를 본다 — 해밍이 먼 최고 청크가 점수에 반영된다")
    void 퍼널_Stage2는_전_청크를_본다() {
        Map<Long, Double> funnel = crossScoreTwoStage(allIds(), NO_FLOOR, 10);

        // near의 Stage 1 추정은 해밍 상위 3개(유사도 0.30)로 계산되지만,
        // Stage 2 값은 해밍 500짜리 최고 청크(0.95)를 포함한 상위 3개 평균이어야 한다.
        assertThat(funnel.get(nearArticle.getId())).isCloseTo(0.5167, within(0.01));
    }

    @Test
    @DisplayName("stage2Limit은 Stage 1 추정 상위 아티클만 남긴다")
    void 퍼널_stage2Limit이_상위_아티클만_남긴다() {
        // 추정 유사도 순: near(≈1.00) > mid(≈0.82) > far(≈0.04)
        assertThat(crossScoreTwoStage(survivingIds(), NO_FLOOR, 1))
                .containsOnlyKeys(nearArticle.getId());
        assertThat(crossScoreTwoStage(survivingIds(), NO_FLOOR, 2))
                .containsOnlyKeys(nearArticle.getId(), midArticle.getId());
    }

    @Test
    @DisplayName("Stage 1 하한 미달 아티클은 컷 안이어도 제외된다")
    void 퍼널_하한_미달_아티클은_제외된다() {
        Map<Long, Double> funnel = crossScoreTwoStage(survivingIds(), 0.5, 10);

        // far는 실제 유사도가 0.95로 가장 높지만 해밍이 멀어(추정 0.04) 하한에 걸린다 —
        // 이진 근사가 놓치는 케이스이고, 하한을 임계값보다 넉넉히 낮춰 잡는 이유다.
        assertThat(funnel).containsOnlyKeys(nearArticle.getId(), midArticle.getId());
    }

    @Test
    @DisplayName("deleted_at 아티클은 퍼널 Stage 1 슬롯을 차지한다 — 조인 제거의 대가")
    void 퍼널_deleted_아티클은_Stage1_슬롯을_차지한다() {
        // 2026-08-17 이전에는 stage1의 article 조인이 걸러냈다. 그 조인을 제거해 요청당 PK 조회
        // 1,113회(블록의 21%)를 없앤 대가로, 삭제 아티클이 stage2Limit 슬롯을 하나 쓸 수 있다.
        // deleted는 해밍 0짜리 청크만 가져 추정 1.0으로 1위이므로 컷 1에서 유일한 생존자가 된다.
        // 최종 노출은 ArticleSearchService Phase B의 validArticleIds가 막으므로 결과 정합성에는
        // 영향이 없고, 손해는 "후보 슬롯 하나"로 한정된다.
        // 근거: load-test/results/2026-08-17-osiv-connection-hold-ab.md 6장
        assertThat(crossScoreTwoStage(allIds(), NO_FLOOR, 1))
                .containsOnlyKeys(deletedArticle.getId());
    }

    // ==================== helpers ====================

    private Map<Long, Double> crossScore(List<Long> articleIds) {
        return toScoreMap(chunkRepository.computeSimilarityForArticleIds(
                QUERY_EMBEDDING, idArray(articleIds), TOP_K));
    }

    private Map<Long, Double> crossScoreTwoStage(List<Long> articleIds, double stage1Floor, int stage2Limit) {
        return toScoreMap(chunkRepository.computeSimilarityForArticleIdsTwoStage(
                QUERY_EMBEDDING, QUERY_BINARY, idArray(articleIds), TOP_K, stage1Floor, stage2Limit));
    }

    private List<Long> allIds() {
        return List.of(nearArticle.getId(), midArticle.getId(), farArticle.getId(), deletedArticle.getId());
    }

    /**
     * 삭제되지 않은 아티클만. 컷·하한 메커니즘 테스트는 이 목록을 쓴다 —
     * 2026-08-17부터 쿼리가 deleted_at을 걸러내지 않으므로(서비스 게이트가 담당),
     * allIds()를 쓰면 삭제 아티클이 컷 슬롯을 차지해 메커니즘 검증이 흐려진다.
     */
    private List<Long> survivingIds() {
        return List.of(nearArticle.getId(), midArticle.getId(), farArticle.getId());
    }

    private Map<Long, Double> toScoreMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(
                row -> ((Number) row[0]).longValue(),
                row -> ((Number) row[1]).doubleValue()));
    }

    /** VectorSearchService.formatIdArray와 같은 포맷 (PostgreSQL 배열 리터럴) */
    private static String idArray(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    private Article persistArticle(Corporation corporation) {
        Article article = ArticleCreator.createArticle(corporation);
        entityManager.persist(article.getCategory());
        entityManager.persist(article);
        return article;
    }

    /**
     * @param similarity  질의와의 코사인 유사도 (벡터 첫 성분으로 직접 지정)
     * @param hammingBits 질의 binary(전부 1)와 다르게 둘 비트 수 = 해밍 거리
     */
    private void persistChunk(Article article, int index, double similarity, int hammingBits) {
        ArticleChunk chunk = ArticleChunk.builder()
                .article(article)
                .chunkIndex(index)
                .embeddingBinary(binaryWithZeros(hammingBits))
                .isRepresentative(false)
                .build();
        entityManager.persist(chunk);

        ChunkVector vector = ChunkVector.builder()
                .chunk(chunk)
                .embeddingNormalized(unitVectorWithFirstComponent(similarity))
                .build();
        entityManager.persist(vector);
    }

    private static BitSet binaryWithZeros(int zeroCount) {
        BitSet bits = new BitSet(DIM);
        bits.set(0, DIM);
        if (zeroCount > 0) {
            bits.clear(0, zeroCount);
        }
        return bits;
    }

    /** 첫 성분이 s, 다음 성분이 sqrt(1-s²)인 단위벡터 → e1과의 내적 = s */
    private static float[] unitVectorWithFirstComponent(double s) {
        float[] vector = new float[DIM];
        vector[0] = (float) s;
        vector[1] = (float) Math.sqrt(1.0 - s * s);
        return vector;
    }

    private static String queryEmbedding() {
        StringBuilder sb = new StringBuilder("[1");
        for (int i = 1; i < DIM; i++) {
            sb.append(",0");
        }
        return sb.append("]").toString();
    }
}
