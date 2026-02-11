#!/usr/bin/env python3
"""
Step 4 - Deterministic merge

Reads:
- configurations_descriptions_step_3.json
- step_4_overrides.json

Writes:
- configurations_descriptions_step_4.json

Overrides file format (minimal, extensible):
{
  "lang": "java",
  "addResults": [
    {
      "key": "DD_SOME_KEY",
      "version": "A",
      "result": {
        "description": "High-quality description inferred from code.",
        "shortDescription": "",
        "source": "llm_generated",
        "sourceFile": "internal-api/src/main/java/datadog/trace/api/Config.java:1234"
      }
    }
  ]
}
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


SOURCE_LLM = "llm_generated"


def eprint(*args: Any) -> None:
    print(*args, file=sys.stderr)


def stable_sort_key_version(items: list[dict[str, Any]]) -> None:
    items.sort(key=lambda x: (x.get("key", ""), x.get("version", "")))


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, obj: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write("\n")


def source_rank(source: str) -> int:
    return {
        "registry_doc": 0,
        "documentation_same_language": 1,
        "documentation_other_sources": 2,
        "llm_generated": 3,
    }.get(source, 99)


def sort_results(results: list[dict[str, Any]]) -> None:
    def key_fn(r: dict[str, Any]) -> tuple[int, str, str]:
        s = str(r.get("source", ""))
        sf = str(r.get("sourceFile", "")).strip()
        d = str(r.get("description", "")).strip()
        return (source_rank(s), sf, d)

    results.sort(key=key_fn)


def dedupe_results(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[tuple[str, str, str]] = set()
    out: list[dict[str, Any]] = []
    for r in results:
        if not isinstance(r, dict):
            continue
        source = str(r.get("source", ""))
        desc = str(r.get("description", "")).strip()
        src_file = str(r.get("sourceFile", "")).strip()
        k = (source, src_file, desc)
        if k in seen:
            continue
        seen.add(k)
        out.append(r)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--step-3-input",
        default="./workspace/result/configurations_descriptions_step_3.json",
    )
    ap.add_argument(
        "--step-4-overrides",
        default="./workspace/result/step_4_overrides.json",
    )
    ap.add_argument("--output", default="./workspace/result")
    args = ap.parse_args()

    step3_path = Path(args.step_3_input).resolve()
    overrides_path = Path(args.step_4_overrides).resolve()
    out_dir = Path(args.output).resolve()

    eprint(f"[step4-merge] step3={step3_path}")
    eprint(f"[step4-merge] overrides={overrides_path}")
    eprint(f"[step4-merge] output-dir={out_dir}")

    step3 = read_json(step3_path)
    if not isinstance(step3, dict):
        raise ValueError("Step 3 input is not a JSON object")
    lang = step3.get("lang", "java")

    documented = step3.get("documentedConfigurations", [])
    missing = step3.get("missingConfigurations", [])
    if not isinstance(documented, list) or not isinstance(missing, list):
        raise ValueError("Step 3 input missing documentedConfigurations[] / missingConfigurations[]")

    overrides = read_json(overrides_path) if overrides_path.exists() else {}
    if not isinstance(overrides, dict):
        raise ValueError("Step 4 overrides file is not a JSON object")

    doc_by_pair: dict[tuple[str, str], dict[str, Any]] = {}
    for it in documented:
        if isinstance(it, dict):
            k = it.get("key")
            v = it.get("version")
            if isinstance(k, str) and isinstance(v, str):
                doc_by_pair[(k, v)] = it

    missing_by_pair: dict[tuple[str, str], dict[str, Any]] = {}
    for it in missing:
        if isinstance(it, dict):
            k = it.get("key")
            v = it.get("version")
            if isinstance(k, str) and isinstance(v, str):
                missing_by_pair[(k, v)] = it

    add_results = overrides.get("addResults", [])
    if not isinstance(add_results, list):
        add_results = []

    applied = 0
    for item in add_results:
        if not isinstance(item, dict):
            continue
        k = item.get("key")
        v = item.get("version")
        res = item.get("result")
        if not isinstance(k, str) or not isinstance(v, str) or not isinstance(res, dict):
            continue

        # Basic validation
        if res.get("source") != SOURCE_LLM:
            raise ValueError(f"Step 4 override addResults for {k} {v} must have source={SOURCE_LLM}")
        if not isinstance(res.get("description"), str) or not str(res.get("description")).strip():
            raise ValueError(f"Step 4 override addResults for {k} {v} must include non-empty description")
        if not isinstance(res.get("sourceFile"), str) or not str(res.get("sourceFile")).strip():
            raise ValueError(f"Step 4 override addResults for {k} {v} must include non-empty sourceFile")
        if "shortDescription" not in res:
            res["shortDescription"] = ""

        pair = (k, v)
        if pair in doc_by_pair:
            entry = doc_by_pair[pair]
            results = entry.get("results", [])
            if not isinstance(results, list):
                results = []
            results.append(res)
            results = dedupe_results([r for r in results if isinstance(r, dict)])
            sort_results(results)
            entry["results"] = results
            applied += 1
        elif pair in missing_by_pair:
            miss = missing_by_pair.pop(pair)
            missing_sources = miss.get("missingReasons", [])
            if not isinstance(missing_sources, list):
                missing_sources = []
            new_entry = {
                "key": k,
                "version": v,
                "results": [res],
                "missingSources": missing_sources,
            }
            doc_by_pair[pair] = new_entry
            applied += 1
        else:
            # unknown pair; ignore
            continue

    eprint(f"[step4-merge] applied addResults={applied}")

    new_documented = list(doc_by_pair.values())
    new_missing = list(missing_by_pair.values())
    stable_sort_key_version(new_documented)
    stable_sort_key_version(new_missing)

    out_obj: dict[str, Any] = {
        "lang": lang,
        "missingCount": len(new_missing),
        "documentedCount": len(new_documented),
        "documentedConfigurations": new_documented,
        "missingConfigurations": new_missing,
    }

    out_path = out_dir / "configurations_descriptions_step_4.json"
    write_json(out_path, out_obj)
    eprint(
        f"[step4-merge] wrote {out_path} (documented={len(new_documented)} missing={len(new_missing)})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


