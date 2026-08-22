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


if __name__ == "__main__":
    unittest.main()
