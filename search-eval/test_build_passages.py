#!/usr/bin/env python3
"""build_passages.py 순수 함수 단위 테스트.

실행: python3 -m unittest discover -s search-eval -p 'test_*.py'
"""
import importlib.util
import pathlib
import unittest


SPEC = importlib.util.spec_from_file_location(
    "build_passages", pathlib.Path(__file__).with_name("build_passages.py")
)
BP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BP)


class StripTitlePrefixTest(unittest.TestCase):
    """앱은 buildFullText 에서 "{translatedTitle ?? title}. " 를 본문 앞에 1회 붙인다
    (ChunkEmbeddingBatchService:699). chunk 0 앞머리의 그 접두만 벗겨야 한다."""

    def test_strips_original_title(self):
        text, hit = BP.strip_title_prefix("제목입니다. 본문 시작이다.", "제목입니다", None)
        self.assertEqual("본문 시작이다.", text)
        self.assertEqual("original", hit)

    def test_prefers_translated_title_like_the_app(self):
        text, hit = BP.strip_title_prefix("번역 제목. 본문.", "원제목", "번역 제목")
        self.assertEqual("본문.", text)
        self.assertEqual("translated", hit)

    def test_collapses_title_repeated_by_overlap(self):
        text, _ = BP.strip_title_prefix("제목. 제목. 제목. 본문.", "제목", None)
        self.assertEqual("본문.", text)

    def test_leaves_body_alone_when_title_absent(self):
        text, hit = BP.strip_title_prefix("본문만 있다.", "제목입니다", None)
        self.assertEqual("본문만 있다.", text)
        self.assertIsNone(hit)

    def test_does_not_strip_title_occurring_later(self):
        text, hit = BP.strip_title_prefix("서두. 제목입니다. 뒤에 나온다.", "제목입니다", None)
        self.assertEqual("서두. 제목입니다. 뒤에 나온다.", text)
        self.assertIsNone(hit)


class DeoverlapTest(unittest.TestCase):
    """앱은 getOverlapSentences 로 앞 청크의 마지막 문장들을 뒤 청크 앞에 얹는다
    (한글 우세 문서 overlap 400/1024 토큰 = 39%). 병합 시 그 중복을 걷어내야 한다."""

    def test_removes_shared_leading_sentences(self):
        prev = "가. 나. 다. 라."
        cur = "다. 라. 마. 바."
        self.assertEqual("마. 바.", BP.deoverlap(prev, cur))

    def test_returns_unchanged_when_no_overlap(self):
        self.assertEqual("마. 바.", BP.deoverlap("가. 나.", "마. 바."))

    def test_handles_full_containment(self):
        self.assertEqual("", BP.deoverlap("가. 나.", "가. 나."))


class TokenizerTest(unittest.TestCase):
    """로컬 근사 BM25 용 토크나이저 — 앱의 Nori 가 아니다."""

    def test_latin_runs_stay_whole(self):
        self.assertIn("kubernetes", BP.tokens("Kubernetes 운영"))

    def test_hangul_becomes_bigrams(self):
        self.assertEqual(["운영", "영노", "노하", "하우"], BP.tokens("운영노하우"))

    def test_single_hangul_char_survives(self):
        self.assertEqual(["가"], BP.tokens("가"))


class Bm25Test(unittest.TestCase):
    def test_ranks_matching_document_higher(self):
        docs = [BP.tokens("캐시 무효화 전략"), BP.tokens("프론트엔드 렌더링"), BP.tokens("캐시 적중률")]
        bm = BP.Bm25(docs)
        q = BP.tokens("캐시")
        scores = [bm.score(q, i) for i in range(3)]
        self.assertGreater(scores[0], scores[1])
        self.assertEqual(0.0, scores[1])

    def test_no_lexical_bridge_scores_zero(self):
        """영문 쿼리와 한글 본문 사이에는 어휘 다리가 없다 — 이 공백을 계측해 한계로 보고한다."""
        bm = BP.Bm25([BP.tokens("쿠버네티스 클러스터 운영")])
        self.assertEqual(0.0, bm.score(BP.tokens("kubernetes"), 0))


class JaccardTest(unittest.TestCase):
    def test_identical_text_is_one(self):
        s = BP.shingles("동일한 문장이다")
        self.assertEqual(1.0, BP.jaccard(s, s))

    def test_disjoint_text_is_zero(self):
        self.assertEqual(0.0, BP.jaccard(BP.shingles("가나다라마"), BP.shingles("xyzwv")))

    def test_near_duplicate_exceeds_threshold(self):
        a = BP.shingles("캐시 무효화 전략을 정리한 글이다")
        b = BP.shingles("캐시 무효화 전략을 정리한 글이다 추가")
        self.assertGreaterEqual(BP.jaccard(a, b), BP.JACCARD_DUP)


class TruncateTest(unittest.TestCase):
    def test_keeps_sentence_boundary(self):
        # 한도 8 → 첫 문장(5자)만 들어가고 둘째 문장은 5+1+5=11 로 넘친다.
        text, cut = BP.truncate_sentences("가나다라. 마바사아. 자차카타.", 8)
        self.assertTrue(cut)
        self.assertEqual("가나다라.", text)

    def test_packs_as_many_sentences_as_fit(self):
        text, cut = BP.truncate_sentences("가나다라. 마바사아. 자차카타.", 12)
        self.assertTrue(cut)
        self.assertEqual("가나다라. 마바사아.", text)
        self.assertLessEqual(len(text), 12)

    def test_no_cut_when_within_limit(self):
        text, cut = BP.truncate_sentences("짧다.", 100)
        self.assertFalse(cut)
        self.assertEqual("짧다.", text)


if __name__ == "__main__":
    unittest.main()
