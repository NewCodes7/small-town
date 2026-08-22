#!/usr/bin/env python3
"""가드 회귀 방지 — 낡은 pool.json 으로는 passages.sql 을 만들 수 없어야 한다.

이 가드가 뚫리면 아무 데서도 에러가 나지 않는다: 틀린 아티클 집합으로 SQL 이 생성되고,
prod 가 그 청크를 성실히 뽑아주고, 판정까지 완주해서 '그럴듯한 결과표'가 나온다.
그래서 실패가 눈에 띄지 않는다 — 테스트로 못박아 둔다.
"""
import contextlib
import importlib.util
import io
import json
import os
import pathlib
import tempfile
import unittest


def _load(name):
    spec = importlib.util.spec_from_file_location(
        name, pathlib.Path(__file__).with_name(f"{name}.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


BUILD_POOL = _load("build_pool")
MPS = _load("make_passages_sql")


def write_pool(base, pool):
    os.makedirs(base, exist_ok=True)
    io.open(f"{base}/pool.json", "w", encoding="utf-8").write(
        json.dumps(pool, ensure_ascii=False))


VALID_POOL = {"runId": "t", "topK": 10,
              "provenance": BUILD_POOL.POOL_PROVENANCE,
              "queries": [{"keyword": "k", "tier": "SIMPLE", "poolSize": 1,
                           "pool": [{"articleId": 1, "rankIn": {"hybrid": 1}}]}]}


class LoadPoolTest(unittest.TestCase):
    def test_pool_without_provenance_is_rejected(self):
        """구 build_pool.py 가 만든 풀에는 표식이 없다 — 이게 실제로 막아야 할 상태다."""
        with tempfile.TemporaryDirectory() as d:
            write_pool(d, {k: v for k, v in VALID_POOL.items() if k != "provenance"})
            with self.assertRaises(SystemExit) as cm:
                MPS.load_pool(d)
            self.assertIn("낡은 풀", str(cm.exception))

    def test_pool_with_unknown_provenance_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            write_pool(d, {**VALID_POOL, "provenance": "crossScoredRanks"})
            with self.assertRaises(SystemExit):
                MPS.load_pool(d)

    def test_fresh_pool_is_accepted(self):
        with tempfile.TemporaryDirectory() as d:
            write_pool(d, VALID_POOL)
            self.assertEqual(MPS.load_pool(d)["queries"][0]["keyword"], "k")

    def test_guard_does_not_depend_on_raw_jsonl(self):
        """raw.jsonl 은 gitignore 대상이라 '없는 것이 정상'이다.

        구 가드는 이 상태에서 경고만 찍고 통과했다 — 클론 직후가 정확히 이 상태였다.
        """
        with tempfile.TemporaryDirectory() as d:
            write_pool(d, {k: v for k, v in VALID_POOL.items() if k != "provenance"})
            self.assertFalse(os.path.exists(f"{d}/raw.jsonl"))
            with self.assertRaises(SystemExit):
                MPS.load_pool(d)


class ProvenanceIsActuallyWrittenTest(unittest.TestCase):
    """상수만 맞춰두고 build_pool 이 실제로는 안 찍는 경우를 막는다 (end-to-end)."""

    def test_build_pool_stamps_provenance_into_pool_json(self):
        tiers = ("SIMPLE", "MODERATE", "COMPLEX", "SPECIFIC", "CORPORATION")
        rows = [{"keyword": f"q{i}", "tier": t, "latencyMs": 10,
                 "body": {"totalElements": 2, "content": [
                     {"id": 1, "sourceBm25Rank": 1, "sourceVectorRank": 1, "finalScore": 0.9},
                     {"id": 2, "sourceBm25Rank": 2, "sourceVectorRank": 2, "finalScore": 0.5}]}}
                for i, t in enumerate(tiers)]

        with tempfile.TemporaryDirectory() as d:
            run = os.path.join(d, "search-eval", "runs", "t")
            os.makedirs(run)
            io.open(f"{run}/raw.jsonl", "w", encoding="utf-8").write(
                "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows))

            cwd, run_id = os.getcwd(), os.environ.get("RUN_ID")
            os.chdir(d)
            os.environ["RUN_ID"] = "t"
            try:
                with contextlib.redirect_stdout(io.StringIO()):
                    BUILD_POOL.main()
            finally:
                os.chdir(cwd)
                os.environ.pop("RUN_ID", None)
                if run_id is not None:
                    os.environ["RUN_ID"] = run_id

            pool = json.load(io.open(f"{run}/pool.json", encoding="utf-8"))
            self.assertEqual(pool["provenance"], BUILD_POOL.POOL_PROVENANCE)
            # 그리고 그 풀은 곧바로 가드를 통과해야 한다
            self.assertEqual(MPS.load_pool(run)["runId"], "t")


if __name__ == "__main__":
    unittest.main()
