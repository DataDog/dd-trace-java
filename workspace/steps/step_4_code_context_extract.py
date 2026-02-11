#!/usr/bin/env python3
"""
Step 4 - Deterministic code context extraction

Produces:
- configurations_descriptions_step_4_code_context.json

This script:
- Reads Step 3 output (configurations_descriptions_step_3.json)
- For each still-missing key+version (or a subset via --only-key), extracts code references
  that can be used to infer behavior deterministically.

Notes:
- The output is a context packet (source: code_context). It is NOT a description source.
- Logs go to stderr; JSON is written to disk.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional


SOURCE_CODE = "code_context"


def eprint(*args: Any) -> None:
    print(*args, file=sys.stderr)


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, obj: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write("\n")


@dataclass(frozen=True)
class Pair:
    key: str
    version: str


def stable_sort_key_version(items: list[dict[str, Any]]) -> None:
    items.sort(key=lambda x: (x.get("key", ""), x.get("version", "")))


_JAVA_CONST_RE = re.compile(
    r'public\s+static\s+final\s+String\s+([A-Za-z0-9_]+)\s*=\s*"([^"]+)"\s*;'
)


def normalize_env_from_config_value(value: str) -> str:
    """
    Mirrors the config inversion convention:
    - uppercase
    - replace '.' and '-' with '_'
    - collapse non [A-Z0-9_] to '_'
    - prefix with 'DD_'
    """
    s = value.upper().replace(".", "_").replace("-", "_")
    s = re.sub(r"[^A-Z0-9_]", "_", s)
    s = re.sub(r"_+", "_", s).strip("_")
    return "DD_" + s


def build_config_constant_index(repo_root: Path) -> dict[str, list[dict[str, Any]]]:
    """
    Build index: env var name -> list of { file, line, symbol, value }
    from dd-trace-api/src/main/java/datadog/trace/api/config/*.java
    """
    cfg_dir = repo_root / "dd-trace-api" / "src" / "main" / "java" / "datadog" / "trace" / "api" / "config"
    out: dict[str, list[dict[str, Any]]] = {}
    if not cfg_dir.exists():
        return out

    files = sorted(cfg_dir.rglob("*.java"), key=lambda p: p.relative_to(cfg_dir).as_posix())
    for fp in files:
        try:
            lines = fp.read_text(encoding="utf-8", errors="replace").splitlines()
        except Exception:
            continue
        rel = fp.relative_to(repo_root).as_posix()
        class_name = fp.stem
        for idx, line in enumerate(lines):
            m = _JAVA_CONST_RE.search(line)
            if not m:
                continue
            const = m.group(1)
            value = m.group(2)
            env = normalize_env_from_config_value(value)
            out.setdefault(env, []).append(
                {
                    "file": rel,
                    "line": idx + 1,
                    "source": SOURCE_CODE,
                    "symbol": f"{class_name}.{const}",
                    "value": value,
                }
            )

    for env in out:
        out[env].sort(key=lambda x: (x.get("file", ""), int(x.get("line", 0)), x.get("symbol", "")))
    return out


_GETENV_RE = re.compile(r'getEnv\("([A-Z0-9_]+)"\)')


def find_getenv_references(repo_root: Path, env_key: str) -> list[dict[str, Any]]:
    """
    Best-effort: find direct getEnv("ENV_KEY") references in internal-api Config.java.
    (This covers a subset of configs that are read directly from env vars.)
    """
    cfg_fp = repo_root / "internal-api" / "src" / "main" / "java" / "datadog" / "trace" / "api" / "Config.java"
    if not cfg_fp.exists():
        return []
    try:
        lines = cfg_fp.read_text(encoding="utf-8", errors="replace").splitlines()
    except Exception:
        return []

    rel = cfg_fp.relative_to(repo_root).as_posix()
    refs: list[dict[str, Any]] = []
    for idx, line in enumerate(lines):
        if env_key not in line:
            continue
        # require it to be inside getEnv("...")
        m = _GETENV_RE.search(line)
        if not m:
            continue
        if m.group(1) != env_key:
            continue
        refs.append({"file": rel, "line": idx + 1, "source": SOURCE_CODE})
    return refs


def find_internal_config_references(repo_root: Path, tokens: set[str], max_refs: int = 5) -> list[dict[str, Any]]:
    """
    Best-effort: locate where a config token is read/used in internal-api Config.java.

    We bias toward "configProvider.getXxx(...)" call sites and assignments, and skip import lines.
    """
    if not tokens:
        return []
    cfg_fp = repo_root / "internal-api" / "src" / "main" / "java" / "datadog" / "trace" / "api" / "Config.java"
    if not cfg_fp.exists():
        return []
    try:
        lines = cfg_fp.read_text(encoding="utf-8", errors="replace").splitlines()
    except Exception:
        return []

    rel = cfg_fp.relative_to(repo_root).as_posix()
    hits: list[tuple[int, int]] = []  # (score, line_no)

    def score_line(s: str) -> int:
        st = s.strip()
        if st.startswith("import "):
            return -100
        score = 0
        if "configProvider.get" in s:
            score += 10
        if "this." in s and "=" in s:
            score += 3
        if st.startswith("return "):
            score += 1
        return score

    for idx, line in enumerate(lines):
        if not any(tok in line for tok in tokens):
            continue
        sc = score_line(line)
        if sc <= -100:
            continue
        hits.append((sc, idx + 1))

    # Deterministic selection: highest score first, then earliest line.
    hits.sort(key=lambda x: (-x[0], x[1]))

    refs: list[dict[str, Any]] = []
    seen: set[int] = set()
    for _, ln in hits:
        if ln in seen:
            continue
        seen.add(ln)
        refs.append({"file": rel, "line": ln, "source": SOURCE_CODE})
        if len(refs) >= max_refs:
            break
    return refs


def with_snippet(repo_root: Path, ref: dict[str, Any], context: int = 3) -> dict[str, Any]:
    fp = repo_root / str(ref.get("file", ""))
    line_no = int(ref.get("line", 0))
    if not fp.exists() or line_no <= 0:
        return ref
    try:
        lines = fp.read_text(encoding="utf-8", errors="replace").splitlines()
    except Exception:
        return ref
    start = max(1, line_no - context)
    end = min(len(lines), line_no + context)
    snippet_lines = []
    for ln in range(start, end + 1):
        prefix = ">" if ln == line_no else " "
        snippet_lines.append(f"{prefix}{ln:5d}: {lines[ln - 1]}")
    out = dict(ref)
    out["snippet"] = "\n".join(snippet_lines)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", default="java")
    ap.add_argument("--supported-configurations", default="metadata/supported-configurations.json")
    ap.add_argument(
        "--step-3-input",
        default="./workspace/result/configurations_descriptions_step_3.json",
    )
    ap.add_argument("--repo-root", default=".")
    ap.add_argument("--output", default="./workspace/result")
    ap.add_argument(
        "--only-key",
        default="",
        help="Optional: limit extraction to a single configuration key (canonical name).",
    )
    args = ap.parse_args()

    repo_root = Path(args.repo_root).resolve()
    step3_path = Path(args.step_3_input).resolve()
    out_dir = Path(args.output).resolve()

    step3 = read_json(step3_path)
    if not isinstance(step3, dict):
        raise ValueError("Step 3 input is not a JSON object")

    missing = step3.get("missingConfigurations", [])
    if not isinstance(missing, list):
        raise ValueError("Step 3 input missing missingConfigurations[]")

    only_key = str(args.only_key).strip() if isinstance(args.only_key, str) else ""

    pairs: list[Pair] = []
    for it in missing:
        if not isinstance(it, dict):
            continue
        k = it.get("key")
        v = it.get("version")
        if not isinstance(k, str) or not isinstance(v, str):
            continue
        if only_key and k != only_key:
            continue
        pairs.append(Pair(key=k, version=v))

    pairs.sort(key=lambda p: (p.key, p.version))
    eprint(f"[step4-context] step3={step3_path}")
    eprint(f"[step4-context] repo-root={repo_root}")
    eprint(f"[step4-context] missing pairs selected={len(pairs)} (only_key={only_key!r})")

    const_index = build_config_constant_index(repo_root)

    out_items: list[dict[str, Any]] = []
    for p in pairs:
        refs: list[dict[str, Any]] = []

        # 1) config constant definition(s) (if any)
        const_defs = const_index.get(p.key, [])
        refs.extend(const_defs)

        # 2) where it is read in internal-api Config.java via ConfigProvider (best-effort)
        tokens: set[str] = set()
        for d in const_defs:
            sym = d.get("symbol")
            if isinstance(sym, str) and "." in sym:
                tokens.add(sym.split(".")[-1])
            val = d.get("value")
            if isinstance(val, str) and val:
                tokens.add(val)
        refs.extend(find_internal_config_references(repo_root, tokens, max_refs=5))

        # 2) direct getEnv("DD_...") references in internal-api Config
        refs.extend(find_getenv_references(repo_root, p.key))

        # Dedupe (file,line)
        seen: set[tuple[str, int]] = set()
        deduped: list[dict[str, Any]] = []
        for r in refs:
            f = str(r.get("file", ""))
            ln = int(r.get("line", 0) or 0)
            k = (f, ln)
            if not f or ln <= 0 or k in seen:
                continue
            seen.add(k)
            deduped.append(r)

        # Add snippets + stable order
        deduped.sort(key=lambda r: (str(r.get("file", "")), int(r.get("line", 0))))
        deduped = [with_snippet(repo_root, r, context=4) for r in deduped]

        out_items.append({"key": p.key, "version": p.version, "references": deduped})

    stable_sort_key_version(out_items)
    out_obj: dict[str, Any] = {"lang": step3.get("lang", args.lang), "configurationsToBeAnalyzed": out_items}
    out_path = out_dir / "configurations_descriptions_step_4_code_context.json"
    write_json(out_path, out_obj)
    eprint(f"[step4-context] wrote {out_path} (items={len(out_items)})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


