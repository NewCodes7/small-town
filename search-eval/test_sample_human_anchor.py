#!/usr/bin/env python3
import importlib.util
import io
import json
import pathlib
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "sample_human_anchor", pathlib.Path(__file__).with_name("sample_human_anchor.py")
)
SHA = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SHA)


def fixture(tmp, n_b=40, n_v=40, n_other=40):
    """쿼리 3개 × 카테고리별 아티클로 작은 풀 하나를 만든다.

    B/V/HV 세 카테고리만 있으면 표본 추출·게이트 로직을 전부 통과시킬 수 있다.
    """
    base = pathlib.Path(tmp)
    queries, judgments, inputs = [], [], []
    aid = 1000
    for qi, (kw, tier) in enumerate([("q1", "SIMPLE"), ("q2", "COMPLEX"), ("q3", "CORPORATION")]):
        pool, hyb, bm, vec = [], [], [], []
        for kind, count in (("B", n_b // 3), ("V", n_v // 3), ("HV", n_other // 3)):
            for _ in range(count):
                aid += 1
                pool.append({"articleId": aid})
                if "B" in kind:
                    bm.append(aid)
                if "V" in kind:
                    vec.append(aid)
                if "H" in kind:
                    hyb.append(aid)
                judgments.append({"keyword": kw, "articleId": aid, "tier": tier,
                                  "mode": "passages", "trial": 1, "relevance": aid % 4})
                inputs.append({"keyword": kw, "articleId": aid, "title": f"제목 {aid}",
                               "translatedTitle": None, "corporation": "회사", "category": "backend",
                               "totalChunks": 9,
                               "passages": [{"chunkIndex": 2, "text": f"본문 {aid}", "mergedFrom": [2]}]})
        queries.append({"keyword": kw, "tier": tier, "poolSize": len(pool), "pool": pool,
                        "rankings": {"hybrid": hyb[:10], "bm25": bm[:10], "vector": vec[:10]},
                        "rankingDepth": {"hybrid": 50, "bm25": 50, "vector": 50}})
    io.open(base / "pool.json", "w", encoding="utf-8").write(
        json.dumps({"runId": "T", "topK": 10, "queries": queries}, ensure_ascii=False))
    for name, rows in (("judgments.jsonl", judgments), ("judge_inputs.jsonl", inputs)):
        io.open(base / name, "w", encoding="utf-8").write(
            "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows))
    return str(base)


class DrawTest(unittest.TestCase):
    def test_same_seed_gives_same_sample(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = fixture(tmp)
            a = SHA.draw(base, 10, 5, 5, seed=1)[3]
            b = SHA.draw(base, 10, 5, 5, seed=1)[3]
            c = SHA.draw(base, 10, 5, 5, seed=2)[3]
            self.assertEqual(a, b)
            self.assertNotEqual(a, c)

    def test_targeted_strata_draw_only_their_category(self):
        with tempfile.TemporaryDirectory() as tmp:
            strata = SHA.draw(fixture(tmp), 10, 5, 5, seed=7)[3]
            for s in strata:
                if s["stratum"] in ("B", "V"):
                    self.assertEqual(s["stratum"], s["category"])

    def test_no_pair_is_drawn_twice(self):
        with tempfile.TemporaryDirectory() as tmp:
            strata = SHA.draw(fixture(tmp), 10, 5, 5, seed=7)[3]
            keys = [(s["keyword"], s["articleId"]) for s in strata]
            self.assertEqual(len(keys), len(set(keys)))
            self.assertEqual(20, len(keys))

    def test_stops_rather_than_silently_shrinking_a_stratum(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(SystemExit, "V 층 잔여"):
                SHA.draw(fixture(tmp), 10, 5, 999, seed=7)


class SheetTest(unittest.TestCase):
    """시트가 정답을 들고 있으면 블라인드가 아니다 — devtools 로 봐도 없어야 한다."""

    def test_sheet_records_carry_no_answer(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = fixture(tmp)
            strata = SHA.draw(base, 6, 3, 3, seed=5)[3]
            _, records = SHA.sheet_records(base, strata)
            blob = json.dumps(records, ensure_ascii=False)
            for leak in ("llmGrade", "stratum", "relevance", "rankIn", "finalScore"):
                self.assertNotIn(leak, blob)

    def test_prompt_text_matches_what_the_judge_received(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = fixture(tmp)
            strata = SHA.draw(base, 3, 1, 1, seed=5)[3]
            rubric, records = SHA.sheet_records(base, strata)
            self.assertIn("등급", rubric)
            for r in records:
                self.assertIn(f"쿼리: {r['keyword']}", r["promptText"])
                self.assertIn(r["passages"][0]["text"], r["promptText"])

    def test_payload_cannot_close_the_script_block_early(self):
        rendered = SHA.render_sheet("루브릭", [{"text": "</script><img>"}], {"runId": "T"})
        self.assertEqual(1, rendered.count("</script>"))
        self.assertNotIn("__DATA__", rendered)

    def test_span_labels_use_one_based_chunk_positions(self):
        self.assertEqual("3", SHA._span({"chunkIndex": 2, "mergedFrom": [2]}))
        self.assertEqual("3~5", SHA._span({"chunkIndex": 2, "mergedFrom": [2, 3, 4]}))
        self.assertEqual("?", SHA._span({}))


class ReportTest(unittest.TestCase):
    def _run(self, base, shift):
        """shift: 카테고리 -> 사람이 LLM 대비 더한 등급."""
        strata = SHA.draw(base, 12, 6, 6, seed=9)[3]
        sample = pathlib.Path(base) / "human_anchor_sample.json"
        io.open(sample, "w", encoding="utf-8").write(
            json.dumps({"meta": {"seed": 9}, "pairs": strata}, ensure_ascii=False))
        human = pathlib.Path(base) / "human.jsonl"
        io.open(human, "w", encoding="utf-8").write("".join(
            json.dumps({"keyword": s["keyword"], "articleId": s["articleId"],
                        "relevance": max(0, min(3, s["llmGrade"] + shift.get(s["category"], 0)))},
                       ensure_ascii=False) + "\n" for s in strata))
        return SHA.report(base, str(human), str(sample))

    def test_perfect_agreement_passes_both_gates(self):
        with tempfile.TemporaryDirectory() as tmp:
            r, missing = self._run(fixture(tmp), {})
            self.assertEqual([], missing)
            self.assertEqual(1.0, r["headline"]["kappaUnweighted"])
            for name in ("B", "V"):
                self.assertEqual("통과", r["gates"][name]["verdict"])

    def test_upward_shift_on_bm25_only_pairs_trips_only_that_gate(self):
        with tempfile.TemporaryDirectory() as tmp:
            r, _ = self._run(fixture(tmp), {"B": 1})
            self.assertNotEqual("통과", r["gates"]["B"]["verdict"])
            self.assertEqual("통과", r["gates"]["V"]["verdict"])
            self.assertGreater(r["gates"]["B"]["meanDelta(human-llm)"], SHA.GATES["B"]["gate"])

    def test_judge_generosity_shows_as_negative_delta_and_keeps_the_gate(self):
        """사람이 낮게 준다 = 판정자가 후하다. §5-5 예측 방향이고 결론을 강화한다."""
        with tempfile.TemporaryDirectory() as tmp:
            r, _ = self._run(fixture(tmp), {"B": -1, "V": -1})
            self.assertLess(r["gates"]["B"]["meanDelta(human-llm)"], 0)
            self.assertEqual("통과", r["gates"]["B"]["verdict"])
            self.assertIn("하한", r["gates"]["B"]["direction"])

    def test_kappa_uses_the_random_stratum_only(self):
        """표적 층까지 넣어 κ 를 내면 모집단 κ 가 아니다."""
        with tempfile.TemporaryDirectory() as tmp:
            base = fixture(tmp)
            r, _ = self._run(base, {"B": 1, "V": 1})
            strata = json.load(io.open(pathlib.Path(base) / "human_anchor_sample.json",
                                       encoding="utf-8"))["pairs"]
            self.assertEqual(sum(1 for s in strata if s["stratum"] == "random"),
                             r["headline"]["n"])

    def test_unjudged_pairs_are_reported_not_silently_dropped(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = fixture(tmp)
            self._run(base, {})
            human = pathlib.Path(base) / "human.jsonl"
            lines = io.open(human, encoding="utf-8").read().splitlines()
            io.open(human, "w", encoding="utf-8").write("\n".join(lines[:-4]) + "\n")
            r, missing = SHA.report(base, str(human),
                                    str(pathlib.Path(base) / "human_anchor_sample.json"))
            self.assertEqual(4, len(missing))
            self.assertEqual(4, r["missing"])


if __name__ == "__main__":
    unittest.main()
