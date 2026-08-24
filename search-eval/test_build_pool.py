#!/usr/bin/env python3
import importlib.util
import pathlib
import unittest


SPEC = importlib.util.spec_from_file_location(
    "build_pool", pathlib.Path(__file__).with_name("build_pool.py")
)
BUILD_POOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD_POOL)


class RankingsTest(unittest.TestCase):
    def test_uses_pre_cross_scoring_source_ranks(self):
        content = [
            {"id": 1, "sourceBm25Rank": 1, "sourceVectorRank": 2,
             "bm25Rank": 2, "vectorRank": 1, "foundByVector": False},
            {"id": 2, "sourceBm25Rank": 2, "sourceVectorRank": None,
             "bm25Rank": 1, "vectorRank": 3, "foundByVector": False},
            {"id": 3, "sourceBm25Rank": None, "sourceVectorRank": 1,
             "bm25Rank": 3, "vectorRank": 2, "foundByVector": True},
        ]

        result = BUILD_POOL.rankings(content)

        self.assertEqual([1, 2], [a["id"] for a in result["bm25"]])
        self.assertEqual([3, 1], [a["id"] for a in result["vector"]])

    def test_rejects_legacy_response_without_source_ranks(self):
        with self.assertRaisesRegex(ValueError, "재수집"):
            BUILD_POOL.rankings([
                {"id": 1, "bm25Rank": 1, "vectorRank": 1, "foundByVector": False}
            ])

    def test_rejects_duplicate_or_non_contiguous_source_ranks(self):
        with self.assertRaisesRegex(ValueError, "중복되거나 비연속"):
            BUILD_POOL.rankings([
                {"id": 1, "sourceBm25Rank": 1, "sourceVectorRank": None},
                {"id": 2, "sourceBm25Rank": 1, "sourceVectorRank": None},
            ])


class TierSummaryTest(unittest.TestCase):
    """T8 확장 런은 SIMPLE 한 층만 있다 — 없는 층을 나누다 죽으면 안 된다."""

    def test_single_tier_run_does_not_divide_by_zero(self):
        import io as _io, json, os, pathlib, tempfile, contextlib
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp) / "search-eval" / "runs" / "one-tier"
            d.mkdir(parents=True)
            _io.open(d / "raw.jsonl", "w", encoding="utf-8").write(json.dumps({
                "keyword": "kafka", "tier": "SIMPLE", "appTier": "SIMPLE", "status": 200,
                "latencyMs": 500,
                "body": {"totalElements": 2, "content": [
                    {"id": 1, "sourceBm25Rank": 1, "sourceVectorRank": 1, "finalScore": 0.9,
                     "title": "a", "foundByVector": False},
                    {"id": 2, "sourceBm25Rank": 2, "sourceVectorRank": 2, "finalScore": 0.5,
                     "title": "b", "foundByVector": False}]},
            }, ensure_ascii=False) + "\n")
            cwd = os.getcwd()
            try:
                os.chdir(tmp)
                os.environ["RUN_ID"] = "one-tier"
                with contextlib.redirect_stdout(_io.StringIO()) as out:
                    BUILD_POOL.main()          # 예외 없이 끝나야 한다
            finally:
                os.chdir(cwd)
                os.environ.pop("RUN_ID", None)
            self.assertIn("SIMPLE", out.getvalue())
            self.assertNotIn("MODERATE", out.getvalue())
            self.assertTrue((d / "pool.json").exists())


if __name__ == "__main__":
    unittest.main()
