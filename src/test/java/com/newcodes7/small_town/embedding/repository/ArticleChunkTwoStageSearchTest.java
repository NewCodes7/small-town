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
 * 2단계 검색 native 쿼리를 <b>실제 DB에서 실행</b>해 결과 컬럼 semantics를 검증한다.
 *
 * 다른 검색 테스트는 VectorSearchService/Repository를 스텁하므로 이 쿼리들은 한 번도 실행되지
 * 않는다 — native 쿼리는 부팅 시 검증되지 않아 오타가 있어도 운영 첫 검색에서야 터진다.
 *
 * 특히 candidate_similarity의 "후보 청크가 topK개 이상일 때만" 조건을 지킨다: 그 조건이 없으면
 * 후보에 청크 하나만 걸친 아티클이 AVG 분모 축소로 과대평가되고, cross-scoring 재활용 시
 * 약한 매칭이 부풀어 오른다 (docs/operations/PGSS_SEARCH_COST.md 항목 A).
 */
public class ArticleChunkTwoStageSearchTest extends IntegrationTestBase {

    @Autowired
    private ArticleChunkRepository chunkRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private static final int DIM = 1024;
    private static final int TOP_K = 3;
    private static final double THRESHOLD = 0.5;

    /** 질의 벡터 e1 = [1,0,0,...] — 정규화해도 e1이라 유사도가 청크 벡터의 첫 성분과 같아진다 */
    private static final String QUERY_EMBEDDING = queryEmbedding();

    /** 질의 binary는 전부 1 — 청크의 0 비트 수가 곧 해밍 거리라 후보 순서를 정확히 제어할 수 있다 */
    private static final String QUERY_BINARY = "1".repeat(DIM);

    private Article denseArticle;   // 후보에 청크 4개가 걸리는 아티클
    private Article sparseArticle;  // 후보에 청크 1개만 걸리고, 진짜 최고 청크는 후보 밖인 아티클

    @BeforeEach
    void setUpChunks() {
        Corporation corporation = ArticleCreator.createCorporation(1L);
        entityManager.persist(corporation);

        denseArticle = persistArticle(corporation);
        sparseArticle = persistArticle(corporation);

        // 해밍 거리 0,1,2,3 — 후보 상위를 독점한다
        persistChunk(denseArticle, 0, 0.90, 0);
        persistChunk(denseArticle, 1, 0.80, 1);
        persistChunk(denseArticle, 2, 0.30, 2);   // topK 안이지만 threshold 미만
        persistChunk(denseArticle, 3, 0.20, 3);   // topK 밖

        // 해밍 거리 4 — 후보의 마지막 한 자리를 차지
        persistChunk(sparseArticle, 0, 0.60, 4);
        // 진짜 최고 청크지만 해밍 거리가 멀어 후보에서 탈락한다 (binary 근사의 한계)
        persistChunk(sparseArticle, 1, 0.95, 300);
        persistChunk(sparseArticle, 2, 0.95, 301);

        entityManager.flush();
    }

    @Test
    @DisplayName("후보 청크가 topK 이상이면 candidate_similarity가 채워지고, 미만이면 NULL이다")
    void 후보청크가_topK미만이면_candidate_similarity가_비어야_한다() {
        Map<Long, Object[]> rows = runTwoStageSearch(5, 10);

        // dense: 후보 청크 4개 >= topK 3 → 상위 3개(0.90, 0.80, 0.30) 평균
        Object[] dense = rows.get(denseArticle.getId());
        assertThat(dense).isNotNull();
        assertThat(toDouble(dense[2])).isNotNull().isCloseTo(0.6667, within(0.01));

        // sparse: 후보 청크 1개 < topK 3 → NULL.
        // 0.60을 그대로 넘기면 cross-scoring이 전체 청크로 계산할 값(0.95/0.95/0.60 평균 ≈ 0.83)과
        // 전혀 다른 값이 재활용된다. 분모가 1이라 위로 튀는 경우도 같은 조건이 막는다.
        Object[] sparse = rows.get(sparseArticle.getId());
        assertThat(sparse).isNotNull();
        assertThat(sparse[2]).isNull();
    }

    @Test
    @DisplayName("avg_similarity는 threshold를 통과한 청크만 평균낸다 (candidate_similarity와 다른 값)")
    void avg_similarity는_threshold_통과청크만_평균낸다() {
        Map<Long, Object[]> rows = runTwoStageSearch(5, 10);
        Object[] dense = rows.get(denseArticle.getId());

        // 상위 3개는 0.90/0.80/0.30이지만 threshold 0.5를 넘는 둘만 평균 → 0.85
        assertThat(toDouble(dense[1])).isNotNull().isCloseTo(0.85, within(0.01));
        // 같은 행에서 두 값이 다르다 — 재활용 판정에 avg_similarity를 쓰면 안 되는 이유
        assertThat(toDouble(dense[2])).isNotCloseTo(toDouble(dense[1]), within(0.05));
    }

    @Test
    @DisplayName("limit는 행을 지우지 않고 is_main 플래그로만 표시된다")
    void limit는_is_main으로만_표시된다() {
        Map<Long, Object[]> rows = runTwoStageSearch(5, 1);

        assertThat(rows).hasSize(2);
        // 점수가 높은 dense(0.85)만 본검색 결과, sparse(0.60)는 limit 밖이지만 행은 남는다
        assertThat(rows.get(denseArticle.getId())[3]).isEqualTo(Boolean.TRUE);
        assertThat(rows.get(sparseArticle.getId())[3]).isEqualTo(Boolean.FALSE);
        // limit 밖 아티클의 후보 점수가 살아 있어야 재활용이 성립한다
        assertThat(rows.get(denseArticle.getId())[2]).isNotNull();
    }

    @Test
    @DisplayName("threshold 통과 청크가 없으면 avg_similarity는 NULL, is_main은 false")
    void threshold_미달_아티클은_본검색결과에서_빠진다() {
        List<Object[]> rows = chunkRepository.findArticlesByTwoStageSearch(
                QUERY_EMBEDDING, QUERY_BINARY, 5, TOP_K, 0.95, 10);
        Object[] dense = byArticleId(rows).get(denseArticle.getId());

        assertThat(dense[1]).isNull();
        assertThat(dense[3]).isEqualTo(Boolean.FALSE);
        assertThat(dense[2]).isNotNull();   // 후보 점수는 여전히 계산돼 있다
    }

    @Test
    @DisplayName("필터 변형 4종도 같은 컬럼 형태로 실행된다")
    void 필터_변형_쿼리도_실행된다() {
        List<String> categories = List.of(denseArticle.getCategory().getName());
        List<Integer> domesticTypes = List.of(1);
        Long corporationId = denseArticle.getCorporation().getId();

        assertThat(chunkRepository.findArticlesByTwoStageSearchWithDomesticFilter(
                QUERY_EMBEDDING, QUERY_BINARY, 5, TOP_K, THRESHOLD, 10, domesticTypes))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row).hasSize(4));

        assertThat(chunkRepository.findArticlesByTwoStageSearchWithCategoryFilter(
                QUERY_EMBEDDING, QUERY_BINARY, 5, TOP_K, THRESHOLD, 10, categories))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row).hasSize(4));

        assertThat(chunkRepository.findArticlesByTwoStageSearchWithBothFilters(
                QUERY_EMBEDDING, QUERY_BINARY, 5, TOP_K, THRESHOLD, 10, domesticTypes, categories))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row).hasSize(4));

        assertThat(chunkRepository.findArticlesByTwoStageSearchWithCorporationFilter(
                QUERY_EMBEDDING, QUERY_BINARY, 5, TOP_K, THRESHOLD, 10, List.of(corporationId)))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row).hasSize(4));
    }

    // ==================== helpers ====================

    private Map<Long, Object[]> runTwoStageSearch(int candidateLimit, int limit) {
        return byArticleId(chunkRepository.findArticlesByTwoStageSearch(
                QUERY_EMBEDDING, QUERY_BINARY, candidateLimit, TOP_K, THRESHOLD, limit));
    }

    private Map<Long, Object[]> byArticleId(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(row -> ((Number) row[0]).longValue(), row -> row));
    }

    private Double toDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private Article persistArticle(Corporation corporation) {
        Article article = ArticleCreator.createArticle(corporation);
        entityManager.persist(article.getCategory());
        entityManager.persist(article);
        return article;
    }

    /**
     * @param similarity  질의와의 코사인 유사도 (벡터 첫 성분으로 직접 지정)
     * @param hammingBits 질의 binary(전부 1)와 다르게 둘 비트 수 = 해밍 거리. 작을수록 후보 상위.
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
