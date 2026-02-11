#!/usr/bin/env python3
"""
Step 3 - Deterministic merge

Reads:
- configurations_descriptions_step_3_extracted.json
- step_3_overrides.json

Writes:
- configurations_descriptions_step_3.json

Overrides file format (minimal, extensible):
{
  "lang": "java",
  "rejectResults": [
    {
      "key": "DD_SOME_KEY",
      "version": "A",
      "reason": "quality",
      "result": {
        "description": "Bad / non-self-contained description text to remove (must match exactly).",
        "shortDescription": "",
        "source": "documentation_other_sources",
        "sourceFile": "content/en/path/to/file.md:123"
      }
    }
  ],
  "addResults": [
    {
      "key": "DD_SOME_KEY",
      "version": "A",
      "result": {
        "description": "...",
        "shortDescription": "",
        "source": "documentation_other_sources",
        "sourceFile": "content/en/path/to/file.md:123"
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


SOURCE_DOC = "documentation_other_sources"
DEFAULT_REJECT_REASON = "quality"


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
        key = (source, src_file, desc)
        if key in seen:
            continue
        seen.add(key)
        out.append(r)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--step-3-extracted",
        default="./workspace/result/configurations_descriptions_step_3_extracted.json",
    )
    ap.add_argument(
        "--step-3-overrides",
        default="./workspace/result/step_3_overrides.json",
    )
    ap.add_argument("--output", default="./workspace/result")
    args = ap.parse_args()

    extracted_path = Path(args.step_3_extracted).resolve()
    overrides_path = Path(args.step_3_overrides).resolve()
    out_dir = Path(args.output).resolve()

    eprint(f"[step3-merge] extracted={extracted_path}")
    eprint(f"[step3-merge] overrides={overrides_path}")
    eprint(f"[step3-merge] output-dir={out_dir}")

    extracted = read_json(extracted_path)
    if not isinstance(extracted, dict):
        raise ValueError("Extracted step 3 file is not a JSON object")

    documented = extracted.get("documentedConfigurations", [])
    missing = extracted.get("missingConfigurations", [])
    to_analyze = extracted.get("configurationsToBeAnalyzed", [])
    if not isinstance(documented, list) or not isinstance(missing, list):
        raise ValueError("Extracted file missing documented/missing arrays")
    if not isinstance(to_analyze, list):
        to_analyze = []

    overrides: dict[str, Any]
    if overrides_path.exists():
        raw = read_json(overrides_path)
        overrides = raw if isinstance(raw, dict) else {}
    else:
        overrides = {}

    lang = extracted.get("lang")
    ov_lang = overrides.get("lang")
    if isinstance(lang, str) and isinstance(ov_lang, str) and ov_lang and ov_lang != lang:
        raise ValueError(f"Overrides lang '{ov_lang}' does not match extracted lang '{lang}'")

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

    reject_results = overrides.get("rejectResults", [])
    if not isinstance(reject_results, list):
        reject_results = []

    rejected = 0
    for item in reject_results:
        if not isinstance(item, dict):
            continue
        k = item.get("key")
        v = item.get("version")
        reason = item.get("reason", DEFAULT_REJECT_REASON)
        res = item.get("result")
        if not isinstance(k, str) or not isinstance(v, str) or not isinstance(res, dict):
            continue
        if not isinstance(reason, str) or not reason:
            reason = DEFAULT_REJECT_REASON

        if res.get("source") != SOURCE_DOC:
            raise ValueError(f"Step 3 override rejectResults for {k} {v} must have source={SOURCE_DOC}")
        if not isinstance(res.get("description"), str) or not str(res.get("description")).strip():
            raise ValueError(f"Step 3 override rejectResults for {k} {v} must include non-empty description")
        if not isinstance(res.get("sourceFile"), str) or not str(res.get("sourceFile")).strip():
            raise ValueError(f"Step 3 override rejectResults for {k} {v} must include non-empty sourceFile")

        pair = (k, v)
        entry = doc_by_pair.get(pair)
        if not isinstance(entry, dict):
            # nothing to reject
            continue

        results = entry.get("results", [])
        if not isinstance(results, list):
            results = []

        target_desc = str(res.get("description", "")).strip()
        target_sf = str(res.get("sourceFile", "")).strip()
        target_source = str(res.get("source", "")).strip()

        new_results: list[dict[str, Any]] = []
        removed = 0
        for r in results:
            if not isinstance(r, dict):
                continue
            src = str(r.get("source", "")).strip()
            desc = str(r.get("description", "")).strip()
            sf = str(r.get("sourceFile", "")).strip()
            if (src, sf, desc) == (target_source, target_sf, target_desc):
                removed += 1
                continue
            new_results.append(r)

        if removed == 0:
            existing = []
            for r in results:
                if not isinstance(r, dict):
                    continue
                if str(r.get("source", "")).strip() != SOURCE_DOC:
                    continue
                existing.append(
                    {
                        "sourceFile": str(r.get("sourceFile", "")).strip(),
                        "description": str(r.get("description", "")).strip(),
                    }
                )
            raise ValueError(
                f"Step 3 override rejectResults mismatch for {k} {v}. "
                f"Could not find result with sourceFile={target_sf!r} and exact description. "
                f"Existing {SOURCE_DOC} results for this key: {existing}"
            )

        new_results = dedupe_results([r for r in new_results if isinstance(r, dict)])
        sort_results(new_results)

        if not new_results:
            # Move back to missing: rehydrate prior missingSources (if any) into missingReasons,
            # and mark documentation_other_sources as quality-rejected.
            prior = entry.get("missingSources", [])
            if not isinstance(prior, list):
                prior = []
            missing_reasons = [r for r in prior if isinstance(r, dict)]
            missing_reasons.append({"source": SOURCE_DOC, "reason": reason})
            missing_by_pair[pair] = {"key": k, "version": v, "missingReasons": missing_reasons}
            doc_by_pair.pop(pair, None)
        else:
            entry["results"] = new_results

        rejected += 1

    eprint(f"[step3-merge] applied rejectResults={rejected}")

    add_results = overrides.get("addResults", [])
    if not isinstance(add_results, list):
        add_results = []

    applied = 0
    applied_pairs: set[tuple[str, str]] = set()
    for item in add_results:
        if not isinstance(item, dict):
            continue
        k = item.get("key")
        v = item.get("version")
        res = item.get("result")
        if not isinstance(k, str) or not isinstance(v, str) or not isinstance(res, dict):
            continue

        if res.get("source") != SOURCE_DOC:
            raise ValueError(f"Step 3 override addResults for {k} {v} must have source={SOURCE_DOC}")
        if not isinstance(res.get("description"), str) or not str(res.get("description")).strip():
            raise ValueError(f"Step 3 override addResults for {k} {v} must include non-empty description")
        if not isinstance(res.get("sourceFile"), str) or not str(res.get("sourceFile")).strip():
            raise ValueError(f"Step 3 override addResults for {k} {v} must include non-empty sourceFile")
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
            applied_pairs.add(pair)
        elif pair in missing_by_pair:
            miss = missing_by_pair.pop(pair)
            missing_sources = miss.get("missingReasons", [])
            if not isinstance(missing_sources, list):
                missing_sources = []
            doc_by_pair[pair] = {
                "key": k,
                "version": v,
                "results": [res],
                "missingSources": missing_sources,
            }
            applied += 1
            applied_pairs.add(pair)
        else:
            continue

    eprint(f"[step3-merge] applied addResults={applied}")

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
    if to_analyze:
        # Drop entries that were resolved by overrides (key+version pairs we added results for).
        filtered = []
        for item in to_analyze:
            if not isinstance(item, dict):
                continue
            k = item.get("key")
            v = item.get("version")
            if isinstance(k, str) and isinstance(v, str) and (k, v) in applied_pairs:
                continue
            filtered.append(item)
        filtered.sort(key=lambda x: (x.get("key", ""), x.get("version", "")))
        if filtered:
            out_obj["configurationsToBeAnalyzed"] = filtered

    out_path = out_dir / "configurations_descriptions_step_3.json"
    write_json(out_path, out_obj)
    eprint(
        f"[step3-merge] wrote {out_path} (documented={len(new_documented)} missing={len(new_missing)})"
    )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())


