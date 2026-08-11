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
    @DisplayName("deleted_at이 설정된 아티클은 보충 대상에 있어도 제외된다")
    void deleted_아티클은_제외된다() {
        assertThat(crossScore(allIds())).doesNotContainKey(deletedArticle.getId());
    }

    @Test
    @DisplayName("요청한 id만 반환된다 (배열 파라미터가 실제로 필터로 동작)")
    void 요청하지_않은_아티클은_반환되지_않는다() {
        Map<Long, Double> scores = crossScore(List.of(midArticle.getId()));

        assertThat(scores).containsOnlyKeys(midArticle.getId());
    }

    // ==================== helpers ====================

    private Map<Long, Double> crossScore(List<Long> articleIds) {
        return toScoreMap(chunkRepository.computeSimilarityForArticleIds(
                QUERY_EMBEDDING, idArray(articleIds), TOP_K));
    }

    private List<Long> allIds() {
        return List.of(nearArticle.getId(), midArticle.getId(), farArticle.getId(), deletedArticle.getId());
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
