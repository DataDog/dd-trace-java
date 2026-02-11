#!/usr/bin/env python3
"""
Step 1 - Deterministic merge

Reads:
- configurations_descriptions_step_1_extracted.json
- step_1_overrides.json (reject-only)

Writes:
- configurations_descriptions_step_1.json
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


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


def load_rejections(overrides: dict[str, Any]) -> dict[tuple[str, str], dict[str, str]]:
    rej = overrides.get("rejectRegistryDescriptions", [])
    if not isinstance(rej, list):
        return {}
    out: dict[tuple[str, str], dict[str, str]] = {}
    for item in rej:
        if not isinstance(item, dict):
            continue
        key = item.get("key")
        ver = item.get("version")
        reason = item.get("reason", "quality")
        desc = item.get("description")
        if isinstance(key, str) and isinstance(ver, str) and key and ver:
            if not isinstance(reason, str) or not reason:
                reason = "quality"
            if not isinstance(desc, str) or not desc.strip():
                raise ValueError(
                    f"Override rejection for {key} {ver} must include non-empty 'description' "
                    f"(the exact rejected registry description)."
                )
            out[(key, ver)] = {"reason": reason, "description": desc.strip()}
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--step-1-extracted",
        default="./workspace/result/configurations_descriptions_step_1_extracted.json",
    )
    ap.add_argument(
        "--step-1-overrides",
        default="./workspace/result/step_1_overrides.json",
    )
    ap.add_argument("--output", default="./workspace/result")
    args = ap.parse_args()

    extracted_path = Path(args.step_1_extracted).resolve()
    overrides_path = Path(args.step_1_overrides).resolve()
    out_dir = Path(args.output).resolve()

    eprint(f"[step1-merge] extracted={extracted_path}")
    eprint(f"[step1-merge] overrides={overrides_path}")
    eprint(f"[step1-merge] output-dir={out_dir}")

    extracted = read_json(extracted_path)
    if not isinstance(extracted, dict):
        raise ValueError("Extracted step 1 file is not a JSON object")

    lang = extracted.get("lang")
    documented = extracted.get("documentedConfigurations", [])
    missing = extracted.get("missingConfigurations", [])
    if not isinstance(documented, list) or not isinstance(missing, list):
        raise ValueError("Extracted file missing documented/missing arrays")

    overrides: dict[str, Any]
    if overrides_path.exists():
        overrides_raw = read_json(overrides_path)
        overrides = overrides_raw if isinstance(overrides_raw, dict) else {}
    else:
        overrides = {"rejectRegistryDescriptions": []}

    overrides_lang = overrides.get("lang")
    if isinstance(lang, str) and isinstance(overrides_lang, str) and overrides_lang and overrides_lang != lang:
        raise ValueError(f"Overrides lang '{overrides_lang}' does not match extracted lang '{lang}'")

    reject_map = load_rejections(overrides)
    eprint(f"[step1-merge] rejections={len(reject_map)}")

    # Build index for documented and missing
    doc_by_pair: dict[tuple[str, str], dict[str, Any]] = {}
    for item in documented:
        if not isinstance(item, dict):
            continue
        k = item.get("key")
        v = item.get("version")
        if isinstance(k, str) and isinstance(v, str):
            doc_by_pair[(k, v)] = item

    missing_by_pair: dict[tuple[str, str], dict[str, Any]] = {}
    for item in missing:
        if not isinstance(item, dict):
            continue
        k = item.get("key")
        v = item.get("version")
        if isinstance(k, str) and isinstance(v, str):
            missing_by_pair[(k, v)] = item

    # Apply rejections: move from documented -> missing
    for pair, payload in sorted(reject_map.items(), key=lambda x: (x[0][0], x[0][1])):
        if pair not in doc_by_pair:
            continue

        # Remove from documented list (keep deterministic by reconstructing later)
        doc_item = doc_by_pair.pop(pair)

        # Validate override description matches extracted registry description
        results = doc_item.get("results", [])
        if not isinstance(results, list) or not results:
            raise ValueError(f"Documented entry for {pair[0]} {pair[1]} has no results[]")
        first = results[0] if isinstance(results[0], dict) else None
        if not isinstance(first, dict):
            raise ValueError(f"Documented entry for {pair[0]} {pair[1]} has invalid results[0]")
        extracted_desc = first.get("description")
        if not isinstance(extracted_desc, str):
            raise ValueError(f"Documented entry for {pair[0]} {pair[1]} results[0].description missing/invalid")
        extracted_desc_norm = extracted_desc.strip()
        override_desc_norm = payload["description"].strip()
        if extracted_desc_norm != override_desc_norm:
            raise ValueError(
                f"Override description mismatch for {pair[0]} {pair[1]}:\\n"
                f"- extracted: {extracted_desc_norm!r}\\n"
                f"- override:   {override_desc_norm!r}\\n"
                f"Update step_1_overrides.json to match the extracted description."
            )

        # Ensure missing entry exists with registry_doc quality reason
        missing_by_pair[pair] = {
            "key": pair[0],
            "version": pair[1],
            "missingReasons": [{"source": "registry_doc", "reason": payload['reason']}],
        }

    # Reconstruct arrays
    new_documented = list(doc_by_pair.values())
    new_missing = list(missing_by_pair.values())
    stable_sort_key_version(new_documented)
    stable_sort_key_version(new_missing)

    out_obj = {
        "lang": lang,
        "missingCount": len(new_missing),
        "documentedCount": len(new_documented),
        "documentedConfigurations": new_documented,
        "missingConfigurations": new_missing,
    }

    out_path = out_dir / "configurations_descriptions_step_1.json"
    write_json(out_path, out_obj)
    eprint(
        f"[step1-merge] wrote {out_path} (documented={len(new_documented)} missing={len(new_missing)})"
    )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())


