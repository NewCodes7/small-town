#!/usr/bin/env python3
"""agreement.py — 일치도 계산 단위 테스트.

이 수치가 베이스라인 전체의 신뢰도를 떠받친다. 조용히 틀리면 안 된다.
"""
import importlib.util
import pathlib
import unittest

SPEC = importlib.util.spec_from_file_location("agreement", pathlib.Path(__file__).with_name("agreement.py"))
A = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(A)


def tiers_for(keys, tier="SIMPLE"):
    return {k: tier for k in keys}


class AnalyseTest(unittest.TestCase):
    def test_perfect_agreement(self):
        a = {("q", 1): 3, ("q", 2): 0, ("q", 3): 2}
        r = A.analyse(a, dict(a), tiers_for(a))
        self.assertEqual(r["exactAgreement"], 1.0)
        self.assertEqual(r["kappaUnweighted"], 1.0)
        self.assertEqual(r["meanDelta(a-b)"], 0.0)

    def test_only_common_keys_are_compared(self):
        a = {("q", 1): 3, ("q", 2): 0}
        b = {("q", 1): 3, ("q", 9): 1}          # 2 는 b 에 없고 9 는 a 에 없다
        r = A.analyse(a, b, tiers_for(list(a) + list(b)))
        self.assertEqual(r["n"], 1)

    def test_systematic_shift_shows_in_mean_delta(self):
        """한쪽이 일관되게 한 등급 높으면 평균 차로 드러나야 한다."""
        a = {("q", i): 2 for i in range(10)}
        b = {("q", i): 1 for i in range(10)}
        r = A.analyse(a, b, tiers_for(a))
        self.assertEqual(r["meanDelta(a-b)"], 1.0)
        self.assertEqual(r["exactAgreement"], 0.0)

    def test_quadratic_kappa_is_more_lenient_on_adjacent_disagreement(self):
        """인접 등급 불일치에서 두 κ 가 갈린다 — 그래서 둘 다 보고한다."""
        a = {("q", i): g for i, g in enumerate([3, 3, 2, 2, 1, 1, 0, 0])}
        b = {("q", i): g for i, g in enumerate([2, 3, 3, 2, 0, 1, 1, 0])}
        r = A.analyse(a, b, tiers_for(a))
        self.assertGreater(r["kappaQuadraticWeighted"], r["kappaUnweighted"])

    def test_confusion_matrix_rows_are_source_a(self):
        a = {("q", 1): 3, ("q", 2): 3}
        b = {("q", 1): 1, ("q", 2): 1}
        r = A.analyse(a, b, tiers_for(a))
        self.assertEqual(r["confusion"][3][1], 2)
        self.assertEqual(r["confusion"][1][3], 0)


class LoadSourceTest(unittest.TestCase):
    def test_trial_filter_separates_rounds(self):
        import io as _io, json, tempfile, os
        with tempfile.TemporaryDirectory() as d:
            rows = [
                {"keyword": "q", "articleId": 1, "tier": "SIMPLE", "mode": "passages", "trial": 1, "relevance": 3},
                {"keyword": "q", "articleId": 1, "tier": "SIMPLE", "mode": "passages", "trial": 2, "relevance": 2},
                {"keyword": "q", "articleId": 2, "tier": "SIMPLE", "mode": "head1200", "trial": 1, "relevance": 0},
            ]
            _io.open(f"{d}/judgments.jsonl", "w", encoding="utf-8").write(
                "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows))
            t1 = A.load_source("trial:1", d)
            t2 = A.load_source("trial:2", d)
            self.assertEqual(t1, {("q", 1): 3})     # head1200 은 섞이지 않는다
            self.assertEqual(t2, {("q", 1): 2})


if __name__ == "__main__":
    unittest.main()
