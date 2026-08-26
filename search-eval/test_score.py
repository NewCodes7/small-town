#!/usr/bin/env python3
"""score.py 지표 계산 단위 테스트.

지표 코드는 조용히 틀리기 쉽다 — 값이 그럴듯하게 나오기 때문이다.
손으로 계산한 값과 대조하고, 설계가 명시한 예외 처리(빈 팔 N/A, 0 패딩 금지)를 못박는다.
"""
import importlib.util
import math
import pathlib
import unittest

SPEC = importlib.util.spec_from_file_location("score", pathlib.Path(__file__).with_name("score.py"))
S = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(S)


class NdcgTest(unittest.TestCase):
    def test_matches_hand_computation(self):
        """랭킹 [3,0,2], 판정 풀 [3,2,2,0,0] — 손계산 0.7602."""
        got = S.ndcg_at_k([3, 0, 2], [3, 2, 2, 0, 0], S.gain_linear)
        dcg = 3 / math.log2(2) + 0 / math.log2(3) + 2 / math.log2(4)
        idcg = 3 / math.log2(2) + 2 / math.log2(3) + 2 / math.log2(4)
        self.assertAlmostEqual(got, dcg / idcg, places=9)
        self.assertAlmostEqual(got, 0.76019, places=5)

    def test_perfect_ranking_is_one(self):
        self.assertAlmostEqual(S.ndcg_at_k([3, 2, 1], [3, 2, 1], S.gain_linear), 1.0)

    def test_no_relevant_document_is_none_not_zero(self):
        """관련 문서가 하나도 없으면 정의 불가다. 0 으로 보고하면 '틀렸다'로 읽힌다."""
        self.assertIsNone(S.ndcg_at_k([0, 0], [0, 0, 0], S.gain_linear))

    def test_exponential_gain_widens_top_grades(self):
        lin = S.ndcg_at_k([2, 3], [3, 2], S.gain_linear)
        exp = S.ndcg_at_k([2, 3], [3, 2], S.gain_exp)
        self.assertLess(exp, lin)      # 3 을 2 위로 밀면 지수 gain 이 더 크게 벌준다

    def test_short_ranking_is_not_padded(self):
        """깊이 1 짜리 랭킹에 0 을 채워 길이를 맞추지 않는다 — 값이 같아야 한다."""
        short = S.ndcg_at_k([3], [3, 2], S.gain_linear)
        padded = S.ndcg_at_k([3, 0, 0, 0, 0, 0, 0, 0, 0, 0], [3, 2], S.gain_linear)
        self.assertAlmostEqual(short, padded)


class OtherMetricsTest(unittest.TestCase):
    def test_p5_counts_grade_2_and_above(self):
        self.assertAlmostEqual(S.precision_at([3, 2, 1, 0, 0]), 2 / 5)
        self.assertAlmostEqual(S.precision_at([1, 1, 1, 1, 1]), 0.0)

    def test_p5_denominator_is_fixed_at_five(self):
        """분모를 반환 건수로 바꾸면 짧은 랭킹이 부당하게 유리해진다."""
        self.assertAlmostEqual(S.precision_at([3]), 1 / 5)

    def test_pooled_recall_denominator_is_the_judged_pool(self):
        # 풀에 관련 4건, top-10 안에 2건
        self.assertAlmostEqual(S.pooled_recall_at([3, 2, 0], [3, 2, 2, 3, 0]), 2 / 4)

    def test_pooled_recall_is_none_when_pool_has_no_relevant(self):
        self.assertIsNone(S.pooled_recall_at([0], [0, 1, 1]))

    def test_mrr_uses_first_relevant(self):
        self.assertAlmostEqual(S.mrr([0, 1, 2, 3]), 1 / 3)
        self.assertAlmostEqual(S.mrr([3]), 1.0)
        self.assertAlmostEqual(S.mrr([0, 1, 1]), 0.0)


class EmptyArmTest(unittest.TestCase):
    """§5-6 — BM25 팔이 통째로 죽는 쿼리가 있다. 0 점이 아니라 N/A 로 빼야 한다."""

    def _pool(self):
        return {"queries": [
            {"keyword": "a", "tier": "SIMPLE", "poolSize": 2,
             "rankings": {"hybrid": [1, 2], "bm25": [1, 2], "vector": [1, 2]},
             "rankingDepth": {"hybrid": 2, "bm25": 2, "vector": 2},
             "pool": [{"articleId": 1}, {"articleId": 2}]},
            {"keyword": "b", "tier": "SIMPLE", "poolSize": 2,
             "rankings": {"hybrid": [3, 4], "bm25": [], "vector": [3, 4]},
             "rankingDepth": {"hybrid": 2, "bm25": 0, "vector": 2},
             "pool": [{"articleId": 3}, {"articleId": 4}]},
        ]}

    def test_empty_arm_excluded_from_mean_and_counted(self):
        grades = {("a", 1): 3, ("a", 2): 0, ("b", 3): 3, ("b", 4): 0}
        rows, missing = S.per_query(self._pool(), grades)
        self.assertEqual(missing, [])
        self.assertTrue(rows[1]["bm25"]["empty"])
        summary = S.summarize(rows)
        self.assertEqual(summary["bm25"]["queries"], 1)      # 쿼리 b 는 빠진다
        self.assertEqual(summary["bm25"]["emptyArm"], 1)
        # 두 쿼리 모두 완벽한 랭킹이므로, 빈 팔을 0 으로 셌다면 0.5 가 됐을 것이다
        self.assertAlmostEqual(summary["bm25"]["ndcg10"], 1.0)
        self.assertAlmostEqual(summary["hybrid"]["ndcg10"], 1.0)

    def test_missing_judgement_is_reported_not_silently_zero(self):
        grades = {("a", 1): 3, ("b", 3): 3, ("b", 4): 0}    # (a,2) 판정 없음
        rows, missing = S.per_query(self._pool(), grades)
        self.assertEqual(missing, [("a", 2)])


class BootstrapTest(unittest.TestCase):
    def test_identical_systems_give_ci_containing_zero(self):
        rows = [{"hybrid": {"empty": False, "ndcg10": v}, "bm25": {"empty": False, "ndcg10": v}}
                for v in (0.1, 0.5, 0.9, 0.3)]
        r = S.paired_bootstrap(rows, "hybrid", "bm25", "ndcg10", n=500)
        self.assertEqual(r["meanDelta"], 0.0)
        self.assertFalse(r["significant"])

    def test_uniformly_better_system_is_significant(self):
        rows = [{"hybrid": {"empty": False, "ndcg10": v + 0.2}, "bm25": {"empty": False, "ndcg10": v}}
                for v in (0.1, 0.2, 0.3, 0.4, 0.5, 0.6)]
        r = S.paired_bootstrap(rows, "hybrid", "bm25", "ndcg10", n=2000)
        self.assertAlmostEqual(r["meanDelta"], 0.2, places=6)
        self.assertTrue(r["significant"])


if __name__ == "__main__":
    unittest.main()
