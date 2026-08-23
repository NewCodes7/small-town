#!/usr/bin/env python3
"""judge.py 순수 함수 단위 테스트 (API 호출 없음).

실행: python3 -m unittest discover -s search-eval -p 'test_*.py'
"""
import importlib.util
import pathlib
import unittest


SPEC = importlib.util.spec_from_file_location(
    "judge", pathlib.Path(__file__).with_name("judge.py")
)
J = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(J)


def rec(passages, **kw):
    base = {"keyword": "캐시 무효화", "articleId": 1, "tier": "MODERATE",
            "title": "캐시 이야기", "corporation": "토스", "category": "backend",
            "totalChunks": 8,
            "passages": [{"chunkIndex": i, "text": t, "mergedFrom": m}
                         for i, t, m in passages]}
    base.update(kw)
    return base


class PromptTest(unittest.TestCase):
    def test_hides_system_and_arm_information(self):
        p = J.build_user_prompt(rec([(2, "본문 가.", [2])]), "passages")
        for leaked in ("bm25", "vector", "hybrid", "rank", "score", "RRF"):
            self.assertNotIn(leaked.lower(), p.lower())

    def test_single_chunk_shows_one_based_position(self):
        p = J.build_user_prompt(rec([(2, "본문 가.", [2])]), "passages")
        self.assertIn("전체 8개 구간 중 3번째", p)

    def test_merged_run_shows_a_range_not_the_last_index(self):
        """인접 병합 후 chunkIndex 는 런의 마지막 값이라 그대로 쓰면 오해를 준다."""
        p = J.build_user_prompt(rec([(3, "가. 나. 다.", [1, 2, 3])]), "passages")
        self.assertIn("전체 8개 구간 중 2~4번째", p)

    def test_head1200_mode_uses_excerpt(self):
        r = rec([(0, "안 쓰인다.", [0])], _excerpt="앞부분 발췌다.")
        p = J.build_user_prompt(r, "head1200")
        self.assertIn("앞부분 발췌다.", p)
        self.assertNotIn("안 쓰인다.", p)


class GroundingTest(unittest.TestCase):
    def test_quote_inside_one_passage_is_grounded(self):
        hays = J.haystacks(rec([(0, "캐시를 무효화했다.", [0]), (4, "다른 이야기.", [4])]), "passages")
        self.assertTrue(J.grounded("캐시를 무효화했다.", hays))

    def test_whitespace_is_normalized_before_matching(self):
        hays = J.haystacks(rec([(0, "캐시를   무효화했다.", [0])]), "passages")
        self.assertTrue(J.grounded("캐시를 무효화했다.", hays))

    def test_quote_spanning_two_passages_is_not_grounded(self):
        """이어붙인 한 덩어리로 검증하면 원문에 없는 인용이 통과해 버린다."""
        hays = J.haystacks(rec([(0, "앞 문장이다.", [0]), (5, "뒤 문장이다.", [5])]), "passages")
        self.assertFalse(J.grounded("앞 문장이다. 뒤 문장이다.", hays))

    def test_invented_quote_is_not_grounded(self):
        hays = J.haystacks(rec([(0, "실제 본문.", [0])]), "passages")
        self.assertFalse(J.grounded("지어낸 문장이다.", hays))


class CacheKeyTest(unittest.TestCase):
    def test_passage_set_change_invalidates_cache(self):
        a = rec([(0, "가.", [0])], passageSetHash="aaaa")
        b = rec([(0, "가.", [0])], passageSetHash="bbbb")
        self.assertNotEqual(J.cache_key(a, "passages", 1), J.cache_key(b, "passages", 1))

    def test_mode_and_trial_are_part_of_the_key(self):
        a = rec([(0, "가.", [0])], passageSetHash="aaaa", _excerpt="가.")
        self.assertNotEqual(J.cache_key(a, "passages", 1), J.cache_key(a, "head1200", 1))
        self.assertNotEqual(J.cache_key(a, "passages", 1), J.cache_key(a, "passages", 2))

    def test_same_input_is_stable(self):
        a = rec([(0, "가.", [0])], passageSetHash="aaaa")
        self.assertEqual(J.cache_key(a, "passages", 1), J.cache_key(a, "passages", 1))


if __name__ == "__main__":
    unittest.main()


class TypographicNormalizationTest(unittest.TestCase):
    """판정자는 원문의 굽은 따옴표를 곧은 따옴표로 바꿔 인용한다.

    그걸 '원문에 없는 인용'으로 세면 근거율이 실제보다 낮게 나온다 —
    2026-08-22c 실측 needsReview 4건 중 3건이 이 문제였다.
    """

    def test_curly_apostrophe_quote_is_grounded(self):
        hay = [J.norm("the Python runtime can pause a coroutine that’s waiting")]
        self.assertTrue(J.grounded("a coroutine that's waiting", hay))

    def test_curly_double_quotes_and_dashes_fold(self):
        hay = [J.norm("“blue–green” deployment")]
        self.assertTrue(J.grounded('"blue-green" deployment', hay))

    def test_genuinely_absent_quote_is_still_not_grounded(self):
        """정규화는 타이포그래피만 접는다 — 없는 문장을 만들어내면 안 된다."""
        hay = [J.norm("코루틴은 경량 스레드다")]
        self.assertFalse(J.grounded("코루틴은 무거운 스레드다", hay))

    def test_nbsp_and_zero_width_are_folded(self):
        hay = [J.norm("G1 GC​ 를 사용한다")]
        self.assertTrue(J.grounded("G1 GC 를 사용한다", hay))
