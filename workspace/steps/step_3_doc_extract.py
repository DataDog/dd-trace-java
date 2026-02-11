#!/usr/bin/env python3
"""
Step 3 - Other sources documentation extract (deterministic extraction)

Produces: configurations_descriptions_step_3_extracted.json

This script:
- Reads Step 2 output (configurations_descriptions_step_2.json)
- Scans "other sources" docs files (all content/<locale>/*.md excluding Step 2 same-language docs set)
- Deterministically extracts descriptions from standard "Environment Variable" blocks
- Adds:
  - documentation_other_sources results (with sourceFile)
  - missingReasons for documentation_other_sources on still-missing keys
  - configurationsToBeAnalyzed references when a key is mentioned in an env-var block but cannot
    be deterministically extracted
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional


def eprint(*args: Any) -> None:
    print(*args, file=sys.stderr)


SOURCE_DOC = "documentation_other_sources"


@dataclass(frozen=True)
class Pair:
    key: str
    version: str


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


def sort_results(results: list[dict[str, Any]]) -> None:
    def key_fn(r: dict[str, Any]) -> tuple[int, str, str]:
        s = str(r.get("source", ""))
        sf = str(r.get("sourceFile", "")).strip()
        d = str(r.get("description", "")).strip()
        return (source_rank(s), sf, d)

    results.sort(key=key_fn)


def load_aliases_by_pair(supported_path: Path) -> dict[Pair, tuple[str, ...]]:
    data = read_json(supported_path)
    supported = data.get("supportedConfigurations")
    if not isinstance(supported, dict):
        return {}

    out: dict[Pair, tuple[str, ...]] = {}
    for key, entries in supported.items():
        if not isinstance(key, str) or not isinstance(entries, list):
            continue
        for ent in entries:
            if not isinstance(ent, dict):
                continue
            ver = ent.get("version")
            if not isinstance(ver, str) or not ver:
                continue
            aliases = ent.get("aliases", [])
            if not isinstance(aliases, list):
                aliases = []
            alias_tuple = tuple(sorted({a for a in aliases if isinstance(a, str) and a}))
            out[Pair(key=key, version=ver)] = alias_tuple
    return out


def gather_same_language_doc_files(doc_folder: Path, locale: str, lang: str) -> set[Path]:
    """
    Implements the README's Step 2 include rules (path-based).
    Returns absolute Paths.
    """
    root = doc_folder / "content" / locale
    files: set[Path] = set()

    exact = root / "tracing" / "trace_collection" / "library_config" / f"{lang}.md"
    if exact.exists():
        files.add(exact)

    for fp in root.rglob(f"{lang}.md"):
        if fp.is_file():
            files.add(fp)

    for fp in root.rglob(f"*_{lang}.md"):
        if fp.is_file():
            files.add(fp)

    for fp in root.rglob("*.md"):
        if fp.is_file() and lang in fp.parts:
            files.add(fp)

    return files


def gather_other_sources_doc_files(doc_folder: Path, locale: str, lang: str) -> list[Path]:
    root = doc_folder / "content" / locale
    same = gather_same_language_doc_files(doc_folder, locale, lang)
    out: list[Path] = []
    for fp in root.rglob("*.md"):
        if not fp.is_file():
            continue
        if fp in same:
            continue
        out.append(fp)
    out.sort(key=lambda p: p.relative_to(root).as_posix())
    return out


# Only treat structured config blocks as environment-variable headers.
# This avoids mis-parsing prose mentions like "set the environment variable `DD_FOO` ..."
# Also treat "**Datadog convention**:" blocks as structured config headers in some docs (e.g. OTel mapping docs).
_ENV_VAR_BLOCK_HEADER_RE = re.compile(
    r"\*\*(?:Environment Variable(?:\s*\([^)]*\))?|Datadog convention)\*\*\s*:",
    re.IGNORECASE,
)
_BACKTICK_TOKEN_RE = re.compile(r"`((?:DD|OTEL)_[A-Z0-9_]+)`")
# Ignore tokens explicitly negated in docs like `!DD_INTEGRATIONS_ENABLED`
_RAW_TOKEN_RE = re.compile(r"(?<![!])\b(?:DD|OTEL)_[A-Z0-9_]+\b")
_DEF_LIST_PREFIX_RE = re.compile(r"^\s*:+\s*")


def strip_def_list_prefix(s: str) -> str:
    # Markdown definition lists use ":" / "::" prefixes on definition lines.
    # Example:
    #   `DD_ENV`
    #   :: Environment where ...
    return _DEF_LIST_PREFIX_RE.sub("", s)


def clean_table_cell_text(s: str) -> str:
    s = s.strip()
    s = re.sub(r"<br\\s*/?>", " ", s, flags=re.IGNORECASE)
    s = re.sub(r"\\s+", " ", s).strip()
    return s


def split_md_table_row(line: str) -> list[str]:
    if not line.lstrip().startswith("|"):
        return []
    parts = [p.strip() for p in line.strip().strip("|").split("|")]
    return parts


def is_md_table_separator(parts: list[str]) -> bool:
    # separator rows are like: | --- | :---: | ---: |
    if not parts:
        return False
    for p in parts:
        t = p.strip()
        if not t:
            return False
        if not all(ch in "-: " for ch in t):
            return False
        if "-" not in t:
            return False
    return True


def find_table_column(parts: list[str], needle: str) -> Optional[int]:
    needle = needle.lower()
    for i, p in enumerate(parts):
        if needle in p.lower():
            return i
    return None


def find_env_var_table_column(parts: list[str]) -> Optional[int]:
    """
    Return the column index that contains an environment variable name.

    Many Datadog docs use "Environment Variable", but some tables use "Name"
    as the column header (with values like `DD_FOO` (required)).
    """
    for needle in ("environment variable", "env variable", "env var", "name"):
        col = find_table_column(parts, needle)
        if col is not None:
            return col
    return None


def extract_single_env_token(text: str) -> Optional[str]:
    toks = set(_BACKTICK_TOKEN_RE.findall(text))
    toks |= set(_RAW_TOKEN_RE.findall(text))
    if len(toks) != 1:
        return None
    return next(iter(toks))


def extract_markdown_table_row_description(line: str) -> Optional[str]:
    """
    Extract description from a markdown table row of the form:
      | `DD_FOO` | `dd.foo` | Some description |

    Returns the (cleaned) description column text, or None if not a table row.
    """
    if not line.lstrip().startswith("|"):
        return None
    if "DD_" not in line and "OTEL_" not in line:
        return None
    parts = [p.strip() for p in line.strip().strip("|").split("|")]
    if len(parts) < 3:
        return None
    desc = "|".join(parts[2:]).strip()
    if not desc:
        return None
    desc = re.sub(r"<br\\s*/?>", " ", desc, flags=re.IGNORECASE)
    desc = re.sub(r"\\s+", " ", desc).strip()
    return desc or None


def is_metadata_line(line: str) -> bool:
    s = line.strip()
    if not s:
        return True
    s = strip_def_list_prefix(s).strip()
    lower = s.lower()

    # Common metadata fields in config blocks (including variants like "(Deprecated)")
    if lower.startswith("**environment variable"):
        return True
    if lower.startswith("**system property"):
        return True
    if lower.startswith("**datadog convention**"):
        return True

    meta_prefixes = (
        "**default**",
        "**example**",
        "**allowed values**",
        "**accepted values**",
        "**type**",
        "**note**",
    )
    return lower.startswith(meta_prefixes)


def is_new_section_line(line: str) -> bool:
    s = line.strip()
    if not s:
        return False
    if s.startswith("#"):
        return True
    # Definition-list term lines sometimes have extra suffixes (e.g. "(Required)")
    # and may not end with a backtick.
    if s.startswith("`") and extract_single_env_token(s) is not None:
        return True
    if _ENV_VAR_BLOCK_HEADER_RE.search(s):
        return True
    return False


def extract_description(lines: list[str], env_var_line_idx: int) -> tuple[Optional[str], Optional[int]]:
    i = env_var_line_idx + 1
    while i < len(lines) and is_metadata_line(lines[i]):
        i += 1
    if i >= len(lines):
        return (None, None)

    collected: list[str] = []
    start_line = i + 1  # 1-based
    while i < len(lines):
        raw = lines[i].rstrip()
        s = strip_def_list_prefix(raw).strip()
        if not s:
            break
        # Metadata lines can appear after the definition line in definition-list style blocks.
        # Skip them rather than including them in the description.
        if is_metadata_line(raw):
            i += 1
            continue
        if is_new_section_line(raw):
            break
        if s.lower().startswith("see also"):
            break
        collected.append(s)
        i += 1

    if not collected:
        return (None, None)

    desc = " ".join(collected).strip()
    desc = re.sub(r"\s+", " ", desc)
    return (desc, start_line)


def passes_quality_bar(desc: str) -> bool:
    s = desc.strip()
    if len(s) < 20:
        return False

    lower = s.lower()

    # Reject obvious non-description / templating / reference-definition content
    if "{{" in s or "}}" in s:
        return False
    if lower.startswith(("{{<", "{{%")):
        return False
    if re.match(r"^\[[^\]]+\]:\s+\S+", s):
        return False
    if s.startswith("```"):
        return False

    # Reject common example/instruction-only starts
    if lower.startswith(("for example", "e.g.", "example:")):
        return False
    if lower.startswith(("- ", "* ")):
        return False
    if "replace `<" in lower:
        return False

    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", default="java")
    ap.add_argument("--supported-configurations", default="metadata/supported-configurations.json")
    ap.add_argument(
        "--step-2-input",
        default="./workspace/result/configurations_descriptions_step_2.json",
    )
    ap.add_argument("--doc-folder", default="./workspace/documentation")
    ap.add_argument("--locale", default="en")
    ap.add_argument("--output", default="./workspace/result")
    args = ap.parse_args()

    supported_path = Path(args.supported_configurations).resolve()
    step2_path = Path(args.step_2_input).resolve()
    doc_folder = Path(args.doc_folder).resolve()
    out_dir = Path(args.output).resolve()
    doc_root = doc_folder / "content" / args.locale

    eprint(f"[step3] step2={step2_path}")
    eprint(f"[step3] supported={supported_path}")
    eprint(f"[step3] doc-root={doc_root}")
    eprint(f"[step3] output-dir={out_dir}")

    step2 = read_json(step2_path)
    if not isinstance(step2, dict):
        raise ValueError("Step 2 input is not a JSON object")

    aliases_by_pair = load_aliases_by_pair(supported_path)

    documented_in = step2.get("documentedConfigurations", [])
    missing_in = step2.get("missingConfigurations", [])
    if not isinstance(documented_in, list) or not isinstance(missing_in, list):
        raise ValueError("Step 2 input missing documentedConfigurations/missingConfigurations arrays")

    documented: dict[Pair, dict[str, Any]] = {}
    missing: dict[Pair, dict[str, Any]] = {}
    all_pairs: list[Pair] = []

    def add_pair(p: Pair) -> None:
        all_pairs.append(p)

    for it in documented_in:
        if not isinstance(it, dict):
            continue
        k = it.get("key")
        v = it.get("version")
        if not isinstance(k, str) or not isinstance(v, str):
            continue
        p = Pair(key=k, version=v)
        add_pair(p)
        results = it.get("results", [])
        if not isinstance(results, list):
            results = []
        documented[p] = {
            "key": k,
            "version": v,
            "results": [r for r in results if isinstance(r, dict)],
            **({"missingSources": it["missingSources"]} if isinstance(it.get("missingSources"), list) else {}),
        }

    for it in missing_in:
        if not isinstance(it, dict):
            continue
        k = it.get("key")
        v = it.get("version")
        if not isinstance(k, str) or not isinstance(v, str):
            continue
        p = Pair(key=k, version=v)
        add_pair(p)
        reasons = it.get("missingReasons", [])
        if not isinstance(reasons, list):
            reasons = []
        missing[p] = {
            "key": k,
            "version": v,
            "missingReasons": [r for r in reasons if isinstance(r, dict)],
        }

    # token -> pairs mapping (canonical + aliases)
    token_to_pairs: dict[str, list[Pair]] = {}
    for p in all_pairs:
        token_to_pairs.setdefault(p.key, []).append(p)
        for a in aliases_by_pair.get(p, ()):
            token_to_pairs.setdefault(a, []).append(p)

    doc_files = gather_other_sources_doc_files(doc_folder, args.locale, args.lang)
    eprint(f"[step3] other-sources doc files: {len(doc_files)}")

    found_hit: dict[Pair, bool] = {}
    extracted_results: dict[Pair, list[dict[str, Any]]] = {}
    refs: dict[Pair, set[tuple[str, int]]] = {}

    for fp in doc_files:
        try:
            lines = fp.read_text(encoding="utf-8", errors="replace").splitlines()
        except Exception as e:
            eprint(f"[step3] WARN: could not read {fp}: {e}")
            continue

        rel_file = fp.relative_to(doc_folder).as_posix()

        table_ctx: Optional[tuple[int, int]] = None  # (env_var_col, description_col)

        for idx, line in enumerate(lines):
            # reset table context when leaving a markdown table
            if not line.lstrip().startswith("|"):
                table_ctx = None

            # 1) Standard "Environment Variable" blocks
            if _ENV_VAR_BLOCK_HEADER_RE.search(line):
                tokens = set(_BACKTICK_TOKEN_RE.findall(line))
                tokens |= set(_RAW_TOKEN_RE.findall(line))
                if not tokens:
                    continue

                tokens = {t for t in tokens if t in token_to_pairs}
                if not tokens:
                    continue

                desc, desc_line = extract_description(lines, idx)
                for t in sorted(tokens):
                    for p in token_to_pairs.get(t, []):
                        found_hit[p] = True
                        if desc is None or desc_line is None or not passes_quality_bar(desc):
                            refs.setdefault(p, set()).add((rel_file, idx + 1))
                            continue
                        source_file = f"{rel_file}:{desc_line}"
                        extracted_results.setdefault(p, []).append(
                            {
                                "description": desc,
                                "shortDescription": "",
                                "source": SOURCE_DOC,
                                "sourceFile": source_file,
                            }
                        )
                continue

            # 1b) Markdown definition-list style blocks:
            #   `DD_FOO` (optional)
            #   :: Description...
            #   **Default**: ...
            if line.lstrip().startswith("`"):
                token = extract_single_env_token(line)
                if token is not None and token in token_to_pairs:
                    j = idx + 1
                    while j < len(lines) and not lines[j].strip():
                        j += 1
                    if j < len(lines) and lines[j].lstrip().startswith(":"):
                        desc, desc_line = extract_description(lines, idx)
                        for p in token_to_pairs.get(token, []):
                            found_hit[p] = True
                            if desc is None or desc_line is None or not passes_quality_bar(desc):
                                refs.setdefault(p, set()).add((rel_file, idx + 1))
                                continue
                            source_file = f"{rel_file}:{desc_line}"
                            extracted_results.setdefault(p, []).append(
                                {
                                    "description": desc,
                                    "shortDescription": "",
                                    "source": SOURCE_DOC,
                                    "sourceFile": source_file,
                                }
                            )
                        continue

            # 2) Markdown tables: only parse tables that have an env-var-ish column AND a Description column.
            if not line.lstrip().startswith("|"):
                continue

            parts = split_md_table_row(line)
            if not parts:
                continue

            if is_md_table_separator(parts):
                continue

            env_col = find_env_var_table_column(parts)
            desc_col = find_table_column(parts, "description")
            if env_col is not None and desc_col is not None:
                table_ctx = (env_col, desc_col)
                continue  # header row

            if table_ctx is None:
                continue

            env_col, desc_col = table_ctx
            if env_col >= len(parts) or desc_col >= len(parts):
                continue

            env_token = extract_single_env_token(parts[env_col])
            if env_token is None or env_token not in token_to_pairs:
                continue

            desc = clean_table_cell_text(parts[desc_col])
            if not desc:
                continue

            desc_line = idx + 1
            for p in token_to_pairs.get(env_token, []):
                found_hit[p] = True
                if not passes_quality_bar(desc):
                    refs.setdefault(p, set()).add((rel_file, idx + 1))
                    continue
                source_file = f"{rel_file}:{desc_line}"
                extracted_results.setdefault(p, []).append(
                    {
                        "description": desc,
                        "shortDescription": "",
                        "source": SOURCE_DOC,
                        "sourceFile": source_file,
                    }
                )

    # Apply extracted results
    for p, results in extracted_results.items():
        results = dedupe_results(results)
        results.sort(key=lambda r: (str(r.get("sourceFile", "")), str(r.get("description", ""))))

        if p in documented:
            existing = documented[p].get("results", [])
            if not isinstance(existing, list):
                existing = []
            documented[p]["results"] = existing + results
        elif p in missing:
            prior_missing = missing.pop(p)
            missing_sources = prior_missing.get("missingReasons", [])
            if not isinstance(missing_sources, list):
                missing_sources = []
            documented[p] = {
                "key": p.key,
                "version": p.version,
                "results": results,
                "missingSources": missing_sources,
            }
        else:
            documented[p] = {"key": p.key, "version": p.version, "results": results}

    # Add missingReasons for documentation_other_sources to still-missing pairs
    for p, miss in list(missing.items()):
        reasons = miss.get("missingReasons", [])
        if not isinstance(reasons, list):
            reasons = []
        already = any(isinstance(r, dict) and r.get("source") == SOURCE_DOC for r in reasons)
        if already:
            continue
        reason = "quality" if found_hit.get(p) else "not_found"
        reasons.append({"source": SOURCE_DOC, "reason": reason})
        miss["missingReasons"] = reasons

    # Finalize results ordering
    doc_list = list(documented.values())
    for it in doc_list:
        results = it.get("results", [])
        if not isinstance(results, list):
            continue
        results = dedupe_results([r for r in results if isinstance(r, dict)])
        sort_results(results)
        it["results"] = results

    missing_list = list(missing.values())
    stable_sort_key_version(doc_list)
    stable_sort_key_version(missing_list)

    # configurationsToBeAnalyzed
    to_analyze: list[dict[str, Any]] = []
    for p, rs in refs.items():
        if p in extracted_results:
            continue
        refs_sorted = sorted(rs, key=lambda x: (x[0], x[1]))
        to_analyze.append(
            {
                "key": p.key,
                "version": p.version,
                "references": [
                    {"file": f, "line": line, "source": SOURCE_DOC} for (f, line) in refs_sorted
                ],
            }
        )
    to_analyze.sort(key=lambda x: (x.get("key", ""), x.get("version", "")))

    out_obj: dict[str, Any] = {
        "lang": step2.get("lang", args.lang),
        "missingCount": len(missing_list),
        "documentedCount": len(doc_list),
        "documentedConfigurations": doc_list,
        "missingConfigurations": missing_list,
    }
    if to_analyze:
        out_obj["configurationsToBeAnalyzed"] = to_analyze

    out_path = out_dir / "configurations_descriptions_step_3_extracted.json"
    write_json(out_path, out_obj)
    eprint(
        f"[step3] wrote {out_path} (documented={len(doc_list)} missing={len(missing_list)} toAnalyze={len(to_analyze)})"
    )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())


