#!/usr/bin/env python3
import contextlib
import importlib.util
import io
import json
import os
import pathlib
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "merge_runs", pathlib.Path(__file__).with_name("merge_runs.py")
)
MERGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MERGE)


def write_run(root, run, rows, judgments=None):
    d = pathlib.Path(root) / "search-eval" / "runs" / run
    d.mkdir(parents=True, exist_ok=True)
    io.open(d / "raw.jsonl", "w", encoding="utf-8").write(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows))
    if judgments is not None:
        io.open(d / "judgments.jsonl", "w", encoding="utf-8").write(
            "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in judgments))
    return d


def raw(keyword, tier):
    return {"keyword": keyword, "tier": tier, "appTier": tier, "status": 200, "body": {}}


def judged(keyword, aid, trial=1, grade=2):
    return {"keyword": keyword, "articleId": aid, "tier": "SIMPLE", "mode": "passages",
            "trial": trial, "relevance": grade}


@contextlib.contextmanager
def in_run(root, dest):
    cwd = os.getcwd()
    os.chdir(root)
    os.environ["RUN_ID"] = dest
    try:
        yield
    finally:
        os.chdir(cwd)
        os.environ.pop("RUN_ID", None)


def run_main(root, dest, argv):
    import sys
    old = sys.argv
    sys.argv = ["merge_runs.py"] + argv
    try:
        with in_run(root, dest), contextlib.redirect_stdout(io.StringIO()) as out:
            MERGE.main()
        return out.getvalue()
    finally:
        sys.argv = old


class MergeTest(unittest.TestCase):
    def test_keeps_only_the_requested_tier(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_run(tmp, "a", [raw("kafka", "SIMPLE"), raw("벡터 검색", "MODERATE")])
            write_run(tmp, "b", [raw("jpa", "SIMPLE")])
            run_main(tmp, "m", ["--from", "a", "--from", "b", "--tier", "SIMPLE"])
            got = [json.loads(l) for l in
                   io.open(f"{tmp}/search-eval/runs/m/raw.jsonl", encoding="utf-8")]
            self.assertEqual(["kafka", "jpa"], [d["keyword"] for d in got])

    def test_stops_on_a_keyword_present_in_two_runs(self):
        """어느 수집본을 쓸지는 사람이 정할 문제다 — 조용히 하나를 고르면 안 된다."""
        with tempfile.TemporaryDirectory() as tmp:
            write_run(tmp, "a", [raw("kafka", "SIMPLE")])
            write_run(tmp, "b", [raw("kafka", "SIMPLE")])
            with self.assertRaisesRegex(SystemExit, "kafka"):
                run_main(tmp, "m", ["--from", "a", "--from", "b", "--tier", "SIMPLE"])

    def test_refuses_to_overwrite_a_source_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_run(tmp, "a", [raw("kafka", "SIMPLE")])
            with self.assertRaisesRegex(SystemExit, "원본을 덮어쓴다"):
                run_main(tmp, "a", ["--from", "a", "--tier", "SIMPLE"])

    def test_carries_judgements_for_merged_keywords_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_run(tmp, "a", [raw("kafka", "SIMPLE"), raw("벡터 검색", "MODERATE")],
                      [judged("kafka", 1), judged("벡터 검색", 2)])
            write_run(tmp, "b", [raw("jpa", "SIMPLE")], [judged("jpa", 3)])
            run_main(tmp, "m", ["--from", "a", "--from", "b", "--tier", "SIMPLE"])
            got = [json.loads(l) for l in
                   io.open(f"{tmp}/search-eval/runs/m/judgments.jsonl", encoding="utf-8")]
            self.assertEqual({"kafka", "jpa"}, {d["keyword"] for d in got})

    def test_keeps_retrial_judgements(self):
        """자기 일치도 분석이 합친 런에서도 돌아야 한다."""
        with tempfile.TemporaryDirectory() as tmp:
            write_run(tmp, "a", [raw("kafka", "SIMPLE")],
                      [judged("kafka", 1, trial=1), judged("kafka", 1, trial=2, grade=3)])
            run_main(tmp, "m", ["--from", "a", "--tier", "SIMPLE"])
            meta = json.load(io.open(f"{tmp}/search-eval/runs/m/merge_meta.json", encoding="utf-8"))
            self.assertEqual({"1": 1, "2": 1}, meta["judgementsByTrial"])

    def test_does_not_copy_pool_json(self):
        """풀은 합친 raw 로 build_pool.py 가 다시 만든다 — 이어 붙이면 무결성 검사를 건너뛴다."""
        with tempfile.TemporaryDirectory() as tmp:
            d = write_run(tmp, "a", [raw("kafka", "SIMPLE")])
            io.open(d / "pool.json", "w", encoding="utf-8").write('{"queries": []}')
            run_main(tmp, "m", ["--from", "a", "--tier", "SIMPLE"])
            self.assertFalse(pathlib.Path(f"{tmp}/search-eval/runs/m/pool.json").exists())

    def test_stops_when_a_source_has_no_matching_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_run(tmp, "a", [raw("벡터 검색", "MODERATE")])
            with self.assertRaisesRegex(SystemExit, "하나도 없다"):
                run_main(tmp, "m", ["--from", "a", "--tier", "SIMPLE"])


if __name__ == "__main__":
    unittest.main()
