#!/usr/bin/env python3
"""
Step 1 - Registry documentation (deterministic extraction)

Produces: configurations_descriptions_step_1_extracted.json

Notes:
- Logs go to stderr; the output JSON file is written to disk.
- Determinism: given the same supported-configurations file and the same registry JSON input,
  this script produces stable output ordering.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Optional
from urllib.request import Request, urlopen


REGISTRY_URL_DEFAULT = "https://dd-feature-parity.azurewebsites.net/configurations/"


@dataclass(frozen=True)
class SupportedKeyVersion:
    key: str
    version: str
    aliases: tuple[str, ...]


def eprint(*args: Any) -> None:
    print(*args, file=sys.stderr)


def normalize_integration_token(name: str) -> str:
    # As per README: uppercase + replace '-' and '.' with '_'
    token = name.upper().replace("-", "_").replace(".", "_")
    # Be conservative: any other non [A-Z0-9_] becomes '_'
    token = re.sub(r"[^A-Z0-9_]", "_", token)
    token = re.sub(r"_+", "_", token).strip("_")
    return token


_JAVA_STRING_RE = re.compile(r'"((?:\\.|[^"\\])*)"')
_AUTOSERVICE_INSTRUMENTER_MODULE_RE = re.compile(
    r"@AutoService\s*\(\s*InstrumenterModule\s*\.\s*class\s*\)"
)
_INSTRUMENTATION_NAMES_METHOD_START_RE = re.compile(
    r"\bString\s*\[\]\s+instrumentationNames\s*\(\s*\)\s*\{",
    re.MULTILINE,
)
_RETURN_NEW_STRING_0_RE = re.compile(r"\breturn\s+new\s+String\s*\[\s*0\s*\]\s*;")
_RETURN_NEW_STRING_ARRAY_RE = re.compile(r"\breturn\s+new\s+String\s*\[\s*\]\s*\{")


def load_instrumentation_name_constant_map(
    path: Optional[Path], lang: str
) -> tuple[dict[str, str], dict[str, str]]:
    """
    Loads a small map to resolve non-literal expressions found in instrumentationNames() arrays.

    File format:
    {
      "lang": "java",
      "expressionToValue": { "REDIS": "redis" },
      "fileExpressionToValue": {
        "dd-java-agent/instrumentation/.../MuleDecorator.java::MULE": "mule"
      }
    }
    """
    if path is None or not path.exists():
        return ({}, {})
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return ({}, {})
    if not isinstance(raw, dict):
        return ({}, {})
    ov_lang = raw.get("lang")
    if isinstance(ov_lang, str) and ov_lang and ov_lang != lang:
        raise ValueError(f"Instrumentation name constants lang '{ov_lang}' does not match '{lang}'")

    expr = raw.get("expressionToValue", {})
    file_expr = raw.get("fileExpressionToValue", {})
    if not isinstance(expr, dict):
        expr = {}
    if not isinstance(file_expr, dict):
        file_expr = {}

    expr_map: dict[str, str] = {}
    for k, v in expr.items():
        if isinstance(k, str) and isinstance(v, str) and k and v:
            expr_map[k.strip()] = v.strip()

    file_expr_map: dict[str, str] = {}
    for k, v in file_expr.items():
        if isinstance(k, str) and isinstance(v, str) and k and v:
            file_expr_map[k.strip()] = v.strip()

    return (expr_map, file_expr_map)


def _strip_java_string_literals(s: str) -> str:
    return _JAVA_STRING_RE.sub('""', s)


def _extract_brace_block(text: str, open_brace_idx: int) -> Optional[tuple[str, int]]:
    """
    Given an index pointing at an opening '{', returns (content_inside, index_after_closing_brace),
    or None if unbalanced.
    Skips braces inside string literals.
    """
    if open_brace_idx < 0 or open_brace_idx >= len(text) or text[open_brace_idx] != "{":
        return None
    i = open_brace_idx + 1
    depth = 1
    in_str = False
    esc = False
    while i < len(text) and depth > 0:
        ch = text[i]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
            i += 1
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
        i += 1
    if depth != 0:
        return None
    # content excludes the braces
    return (text[open_brace_idx + 1 : i - 1], i)


def _extract_paren_block(text: str, open_paren_idx: int) -> Optional[tuple[str, int]]:
    """
    Given an index pointing at an opening '(', returns (content_inside, index_after_closing_paren),
    or None if unbalanced.
    Skips parentheses inside string literals.
    """
    if open_paren_idx < 0 or open_paren_idx >= len(text) or text[open_paren_idx] != "(":
        return None
    i = open_paren_idx + 1
    depth = 1
    in_str = False
    esc = False
    while i < len(text) and depth > 0:
        ch = text[i]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
            i += 1
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        i += 1
    if depth != 0:
        return None
    return (text[open_paren_idx + 1 : i - 1], i)


def _split_top_level_commas(s: str) -> list[str]:
    # Remove comments to simplify splitting
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.DOTALL)
    s = re.sub(r"//.*", "", s)
    out: list[str] = []
    buf: list[str] = []
    depth_paren = 0
    in_str = False
    esc = False
    for ch in s:
        if in_str:
            buf.append(ch)
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
            buf.append(ch)
            continue
        if ch in "([{":
            depth_paren += 1
        elif ch in ")]}":
            depth_paren = max(0, depth_paren - 1)
        if ch == "," and depth_paren == 0:
            part = "".join(buf).strip()
            if part:
                out.append(part)
            buf = []
            continue
        buf.append(ch)
    last = "".join(buf).strip()
    if last:
        out.append(last)
    return out


def _resolve_symbol_in_file(text: str, symbol: str) -> Optional[str]:
    # Best-effort: find a string literal in the initializer of `symbol = ... "literal" ...;`
    pat = re.compile(
        rf'\b{re.escape(symbol)}\b\s*=\s*[^;]*?"((?:\\.|[^"\\])*)"', re.MULTILINE
    )
    m = pat.search(text)
    if not m:
        return None
    return _java_unescape(m.group(1)).strip()


def _resolve_symbol_in_dir(
    directory: Path, symbol: str, cache: dict[tuple[str, str], Optional[str]]
) -> Optional[str]:
    """
    Best-effort: resolve a symbol by scanning .java files in the same directory.
    Cached by (directory, symbol) for performance.
    """
    key = (directory.as_posix(), symbol)
    if key in cache:
        return cache[key]

    try:
        files = sorted(directory.glob("*.java"), key=lambda p: p.name)
    except Exception:
        cache[key] = None
        return None

    for fp in files:
        try:
            text = fp.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        resolved = _resolve_symbol_in_file(text, symbol)
        if resolved:
            cache[key] = resolved
            return resolved

    cache[key] = None
    return None


def _resolve_instrumentation_name_expr(
    expr: str,
    rel_file: str,
    file_path: Path,
    file_text: str,
    expr_map: dict[str, str],
    file_expr_map: dict[str, str],
    dir_symbol_cache: dict[tuple[str, str], Optional[str]],
) -> Optional[str]:
    expr_norm = expr.strip()
    if not expr_norm:
        return None

    # Remove trailing `.toString()` which is common for CharSequence constants
    expr_base = expr_norm
    if expr_base.endswith(".toString()"):
        expr_base = expr_base[: -len(".toString()")].strip()

    # Lookup order: file-specific expr, global expr, then try base variants
    for candidate in (expr_norm, expr_base):
        if not candidate:
            continue
        file_key = f"{rel_file}::{candidate}"
        if file_key in file_expr_map:
            return file_expr_map[file_key]
        if candidate in expr_map:
            return expr_map[candidate]

    # Try resolving from same-file constant initializer (identifier only)
    symbol = expr_base.split(".")[-1].strip()
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", symbol):
        resolved = _resolve_symbol_in_file(file_text, symbol)
        if resolved:
            return resolved
        # Then, try other .java files in the same directory (common for *Constants.java).
        resolved = _resolve_symbol_in_dir(file_path.parent, symbol, dir_symbol_cache)
        if resolved:
            return resolved

    return None


def iter_instrumentation_names(repo_root: Path, constants_path: Optional[Path], lang: str) -> Iterable[str]:
    """
    Yields instrumentation names from methods of the form:
      protected String[] instrumentationNames() { return new String[] { ... }; }
    inside dd-java-agent/instrumentation/**/*.java

    Supports string literals as well as simple constant expressions (IDENT or IDENT.toString()).
    Non-literal expressions can be resolved using an optional constants map file.
    """
    inst_root = repo_root / "dd-java-agent" / "instrumentation"
    if not inst_root.exists():
        return

    expr_map, file_expr_map = load_instrumentation_name_constant_map(constants_path, lang)
    dir_symbol_cache: dict[tuple[str, str], Optional[str]] = {}
    files = sorted(inst_root.rglob("*.java"), key=lambda p: p.relative_to(inst_root).as_posix())
    for fp in files:
        try:
            text = fp.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        if "instrumentationNames" not in text:
            continue

        rel_file = fp.relative_to(repo_root).as_posix()

        for m in _INSTRUMENTATION_NAMES_METHOD_START_RE.finditer(text):
            body_block = _extract_brace_block(text, m.end() - 1)
            if body_block is None:
                continue
            body, _ = body_block
            if _RETURN_NEW_STRING_0_RE.search(body):
                # explicitly ignore return new String[0];
                continue
            for ret in _RETURN_NEW_STRING_ARRAY_RE.finditer(body):
                # locate '{' and parse initializer
                brace_idx = body.find("{", ret.end() - 1)
                if brace_idx == -1:
                    continue
                init_block = _extract_brace_block(body, brace_idx)
                if init_block is None:
                    continue
                init, _ = init_block
                init = init.strip()
                if not init:
                    continue
                for expr in _split_top_level_commas(init):
                    # literal(s)
                    lits = list(_JAVA_STRING_RE.finditer(expr))
                    if lits:
                        for lm in lits:
                            yield _java_unescape(lm.group(1))
                        continue
                    resolved = _resolve_instrumentation_name_expr(
                        expr, rel_file, fp, text, expr_map, file_expr_map, dir_symbol_cache
                    )
                    if resolved:
                        yield resolved
                    else:
                        eprint(
                            f"[step1] WARN: unresolved instrumentationNames expr {expr.strip()!r} in {rel_file}. "
                            f"Add it to the constants map file if it should be treated as an integration name."
                        )


def iter_instrumentation_super_args(
    repo_root: Path, constants_path: Optional[Path], lang: str
) -> Iterable[str]:
    """
    Yields integration/instrumentation names passed to super(...) in instrumentation module classes
    under dd-java-agent/instrumentation.

    Supports both string literals and simple constant expressions (IDENT or IDENT.toString()).
    When an expression can't be resolved, emits a warning (stderr) and skips it.
    """
    inst_root = repo_root / "dd-java-agent" / "instrumentation"
    if not inst_root.exists():
        return

    expr_map, file_expr_map = load_instrumentation_name_constant_map(constants_path, lang)
    dir_symbol_cache: dict[tuple[str, str], Optional[str]] = {}

    files = sorted(inst_root.rglob("*.java"), key=lambda p: p.relative_to(inst_root).as_posix())
    for fp in files:
        try:
            text = fp.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue

        is_instrumentation_file = fp.name.endswith("Instrumentation.java")
        is_autoservice_module = bool(_AUTOSERVICE_INSTRUMENTER_MODULE_RE.search(text))
        if not (is_instrumentation_file or is_autoservice_module):
            continue

        rel_file = fp.relative_to(repo_root).as_posix()

        # Find super(...) calls and extract argument expressions.
        for m in re.finditer(r"\bsuper\s*\(", text):
            open_paren_idx = m.end() - 1
            args_block = _extract_paren_block(text, open_paren_idx)
            if args_block is None:
                continue
            args, _ = args_block
            args = args.strip()
            if not args:
                continue

            for expr in _split_top_level_commas(args):
                lits = list(_JAVA_STRING_RE.finditer(expr))
                if lits:
                    for lm in lits:
                        yield _java_unescape(lm.group(1))
                    continue

                resolved = _resolve_instrumentation_name_expr(
                    expr, rel_file, fp, text, expr_map, file_expr_map, dir_symbol_cache
                )
                if resolved:
                    yield resolved
                else:
                    eprint(
                        f"[step1] WARN: unresolved super(...) expr {expr.strip()!r} in {rel_file}. "
                        f"Add it to the constants map file if it should be treated as an integration name."
                    )


def _java_unescape(s: str) -> str:
    # Good enough for our expected inputs; handles \n, \t, \", \\ and \uXXXX.
    try:
        return bytes(s, "utf-8").decode("unicode_escape")
    except Exception:
        return s


def compute_integration_skip_keys(
    repo_root: Path, supported_keys: set[str], constants_path: Optional[Path], lang: str
) -> set[str]:
    # Collect normalized integration tokens from instrumentation modules.
    tokens: set[str] = set()
    for raw in iter_instrumentation_super_args(repo_root, constants_path, lang):
        if not raw:
            continue
        tokens.add(normalize_integration_token(raw))
    for raw in iter_instrumentation_names(repo_root, constants_path, lang):
        if not raw:
            continue
        tokens.add(normalize_integration_token(raw))

    skip: set[str] = set()
    for tok in sorted(tokens):
        trace_enabled = f"DD_TRACE_{tok}_ENABLED"
        trace_analytics_enabled = f"DD_TRACE_{tok}_ANALYTICS_ENABLED"
        trace_analytics_sample_rate = f"DD_TRACE_{tok}_ANALYTICS_SAMPLE_RATE"
        trace_jmxfetch_enabled = f"DD_TRACE_JMXFETCH_{tok}_ENABLED"

        # Skip patterns (only if present in supported keys)
        for k in (
            trace_enabled,
            trace_analytics_enabled,
            trace_analytics_sample_rate,
            trace_jmxfetch_enabled,
        ):
            if k in supported_keys:
                skip.add(k)

        # If canonical DD_TRACE_<INTEGRATION>_ENABLED doesn't exist, also filter DD_INTEGRATION_<INTEGRATION>_ENABLED
        if trace_enabled not in supported_keys:
            integration_enabled = f"DD_INTEGRATION_{tok}_ENABLED"
            if integration_enabled in supported_keys:
                skip.add(integration_enabled)

    return skip


def compute_filtered_keys_report(
    *,
    lang: str,
    supported: dict[str, Any],
    repo_root: Path,
    constants_path: Optional[Path],
) -> dict[str, Any]:
    """
    Compute the list of keys filtered out by the common "integration toggle" filter.

    Output schema (stable, reviewable):
    {
      "lang": "java",
      "filteredCount": 2,
      "filteredConfigurations": [
        {
          "key": "DD_TRACE_FOO_ENABLED",
          "versions": ["A"],
          "integrationToken": "FOO",
          "pattern": "DD_TRACE_<INTEGRATION>_ENABLED"
        }
      ]
    }
    """
    supported_keys = set(supported.keys())

    # Collect normalized integration tokens (from module super(...) + instrumentationNames()).
    tokens: set[str] = set()
    for raw in iter_instrumentation_super_args(repo_root, constants_path, lang):
        if raw:
            tokens.add(normalize_integration_token(raw))
    for raw in iter_instrumentation_names(repo_root, constants_path, lang):
        if raw:
            tokens.add(normalize_integration_token(raw))

    # Key -> metadata
    filtered: dict[str, dict[str, Any]] = {}

    def versions_for_key(key: str) -> list[str]:
        entries = supported.get(key, [])
        if not isinstance(entries, list):
            return []
        vers = sorted(
            {
                ent.get("version")
                for ent in entries
                if isinstance(ent, dict)
                and isinstance(ent.get("version"), str)
                and ent.get("version")
            }
        )
        return vers

    def add(key: str, tok: str, pattern: str) -> None:
        if key not in supported_keys:
            return
        if key in filtered:
            return
        filtered[key] = {
            "key": key,
            "versions": versions_for_key(key),
            "integrationToken": tok,
            "pattern": pattern,
        }

    for tok in sorted(tokens):
        trace_enabled = f"DD_TRACE_{tok}_ENABLED"
        trace_analytics_enabled = f"DD_TRACE_{tok}_ANALYTICS_ENABLED"
        trace_analytics_sample_rate = f"DD_TRACE_{tok}_ANALYTICS_SAMPLE_RATE"
        trace_jmxfetch_enabled = f"DD_TRACE_JMXFETCH_{tok}_ENABLED"

        add(trace_enabled, tok, "DD_TRACE_<INTEGRATION>_ENABLED")
        add(trace_analytics_enabled, tok, "DD_TRACE_<INTEGRATION>_ANALYTICS_ENABLED")
        add(trace_analytics_sample_rate, tok, "DD_TRACE_<INTEGRATION>_ANALYTICS_SAMPLE_RATE")
        add(trace_jmxfetch_enabled, tok, "DD_TRACE_JMXFETCH_<INTEGRATION>_ENABLED")

        # Fallback: if DD_TRACE_<INTEGRATION>_ENABLED doesn't exist, also filter DD_INTEGRATION_<INTEGRATION>_ENABLED
        if trace_enabled not in supported_keys:
            integration_enabled = f"DD_INTEGRATION_{tok}_ENABLED"
            add(integration_enabled, tok, "DD_INTEGRATION_<INTEGRATION>_ENABLED")

    filtered_list = [filtered[k] for k in sorted(filtered.keys())]
    return {
        "lang": lang,
        "filteredCount": len(filtered_list),
        "filteredConfigurations": filtered_list,
    }


def load_supported_key_versions(
    path: Path, repo_root: Path, constants_path: Optional[Path], lang: str
) -> list[SupportedKeyVersion]:
    data = json.loads(path.read_text(encoding="utf-8"))
    supported = data.get("supportedConfigurations")
    if not isinstance(supported, dict):
        raise ValueError("supported-configurations JSON missing 'supportedConfigurations' dict")

    supported_keys = set(supported.keys())
    skip_keys = compute_integration_skip_keys(repo_root, supported_keys, constants_path, lang)

    out: list[SupportedKeyVersion] = []
    for key in supported.keys():
        if key in skip_keys:
            continue
        entries = supported.get(key)
        if not isinstance(entries, list):
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
            out.append(SupportedKeyVersion(key=key, version=ver, aliases=alias_tuple))

    # stable order (even though later we sort arrays again)
    out.sort(key=lambda kv: (kv.key, kv.version))
    return out


def fetch_registry_json(url: str) -> Any:
    req = Request(url, headers={"User-Agent": "dd-trace-java-step-1/1.0"})
    with urlopen(req, timeout=60) as resp:
        raw = resp.read()
    return json.loads(raw.decode("utf-8"))


def load_registry_json(registry_json_path: Optional[Path], url: str) -> list[dict[str, Any]]:
    if registry_json_path is not None:
        data = json.loads(registry_json_path.read_text(encoding="utf-8"))
    else:
        data = fetch_registry_json(url)

    if not isinstance(data, list):
        raise ValueError("Registry endpoint did not return a JSON array")
    out: list[dict[str, Any]] = []
    for item in data:
        if isinstance(item, dict):
            out.append(item)
    return out


def is_nonempty_description(desc: Any) -> bool:
    if desc is None:
        return False
    if not isinstance(desc, str):
        return False
    s = desc.strip()
    if not s:
        return False
    if s.lower() == "null":
        return False
    return True


def passes_quality_bar(desc: str) -> bool:
    return len(desc.strip()) >= 20


def to_rank(to_value: Any) -> tuple[int, int, int, int, str]:
    """
    Larger rank means 'higher' to-version.
    - null / "latest" => highest (category 2)
    - semver-ish vX.Y.Z => category 1 + parsed ints
    - unknown => category 0 + raw string
    """
    if to_value is None:
        return (2, 0, 0, 0, "")
    if isinstance(to_value, str):
        s = to_value.strip()
        if not s:
            return (0, 0, 0, 0, "")
        if s.lower() == "latest":
            return (2, 0, 0, 0, "latest")
        m = re.search(r"v?(\d+)\.(\d+)\.(\d+)", s)
        if m:
            return (1, int(m.group(1)), int(m.group(2)), int(m.group(3)), s)
        return (0, 0, 0, 0, s)
    return (0, 0, 0, 0, str(to_value))


def pick_registry_configuration_record(
    configurations: list[Any], *, supported_version: str, lang: str
) -> Optional[dict[str, Any]]:
    # 1) Prefer same version
    same_ver: list[dict[str, Any]] = []
    for c in configurations:
        if isinstance(c, dict) and c.get("version") == supported_version:
            same_ver.append(c)
    if same_ver:
        # deterministic: first in source order
        return same_ver[0]

    # 2) Prefer record that includes language == lang with highest 'to'
    lang_lower = lang.lower()
    best: Optional[dict[str, Any]] = None
    best_rank: Optional[tuple[int, int, int, int, str]] = None
    for c in configurations:
        if not isinstance(c, dict):
            continue
        impls = c.get("implementations", [])
        if not isinstance(impls, list):
            continue
        ranks = []
        for impl in impls:
            if not isinstance(impl, dict):
                continue
            if str(impl.get("language", "")).lower() != lang_lower:
                continue
            ranks.append(to_rank(impl.get("to")))
        if not ranks:
            continue
        r = max(ranks)
        if best is None or (best_rank is not None and r > best_rank):
            best = c
            best_rank = r
        elif best is None and best_rank is None:
            best = c
            best_rank = r

    if best is not None:
        return best

    # 3) First record with non-empty description
    for c in configurations:
        if isinstance(c, dict) and is_nonempty_description(c.get("description")):
            return c

    return None


def stable_sort_key_version(items: list[dict[str, Any]]) -> None:
    items.sort(key=lambda x: (x.get("key", ""), x.get("version", "")))


def write_json_file(path: Path, obj: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write("\n")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", default="java")
    ap.add_argument(
        "--supported-configurations",
        default="metadata/supported-configurations.json",
        help="Path to metadata/supported-configurations.json",
    )
    ap.add_argument(
        "--output",
        default="./workspace/result",
        help="Output directory (will create configurations_descriptions_step_1_extracted.json)",
    )
    ap.add_argument(
        "--registry-url",
        default=REGISTRY_URL_DEFAULT,
        help="Registry endpoint URL (ignored if --registry-json is provided)",
    )
    ap.add_argument(
        "--registry-json",
        default=None,
        help="Optional path to a local registry JSON snapshot (for offline/reproducible runs)",
    )
    ap.add_argument(
        "--repo-root",
        default=".",
        help="Path to the dd-trace-java checkout (used for integration filtering)",
    )
    ap.add_argument(
        "--instrumentation-name-constant-map",
        default="./workspace/result/instrumentation_name_constant_map.json",
        help=(
            "Optional JSON map to resolve non-literal expressions in instrumentationNames() arrays "
            "(may not exist / may be empty)."
        ),
    )
    ap.add_argument(
        "--filtered-keys-output",
        default="filtered_configuration_keys.json",
        help=(
            "File name (under --output dir) to write the list of filtered-out keys "
            "due to common integration toggle filtering."
        ),
    )

    args = ap.parse_args()

    repo_root = Path(args.repo_root).resolve()
    supported_path = Path(args.supported_configurations).resolve()
    out_dir = Path(args.output).resolve()
    registry_json_path = Path(args.registry_json).resolve() if args.registry_json else None
    constants_path = (
        Path(args.instrumentation_name_constant_map).resolve()
        if isinstance(args.instrumentation_name_constant_map, str)
        and args.instrumentation_name_constant_map.strip()
        else None
    )

    eprint(f"[step1] repo-root={repo_root}")
    eprint(f"[step1] supported-configurations={supported_path}")
    eprint(f"[step1] output-dir={out_dir}")

    # Compute + write filtered-key report (deterministic)
    supported_data = json.loads(supported_path.read_text(encoding="utf-8"))
    supported_cfgs = supported_data.get("supportedConfigurations")
    if not isinstance(supported_cfgs, dict):
        raise ValueError("supported-configurations JSON missing 'supportedConfigurations' dict")

    filtered_report = compute_filtered_keys_report(
        lang=args.lang,
        supported=supported_cfgs,
        repo_root=repo_root,
        constants_path=constants_path,
    )
    filtered_path = out_dir / str(args.filtered_keys_output)
    write_json_file(filtered_path, filtered_report)
    eprint(
        f"[step1] wrote filtered keys report: {filtered_path} (filtered={filtered_report.get('filteredCount')})"
    )

    key_versions = load_supported_key_versions(supported_path, repo_root, constants_path, args.lang)
    eprint(f"[step1] supported key+version pairs after filters: {len(key_versions)}")

    registry_items = load_registry_json(registry_json_path, args.registry_url)
    registry_by_key: dict[str, dict[str, Any]] = {}
    for it in registry_items:
        name = it.get("name")
        if isinstance(name, str) and name:
            # deterministic: keep first occurrence
            registry_by_key.setdefault(name, it)
    eprint(f"[step1] registry keys indexed: {len(registry_by_key)}")

    documented: list[dict[str, Any]] = []
    missing: list[dict[str, Any]] = []

    seen_pairs: set[tuple[str, str]] = set()

    for kv in key_versions:
        pair = (kv.key, kv.version)
        if pair in seen_pairs:
            # shouldn't happen; ignore duplicates deterministically
            continue
        seen_pairs.add(pair)

        # registry match (canonical first, then aliases)
        reg_entry = registry_by_key.get(kv.key)
        if reg_entry is None and kv.aliases:
            for alias in kv.aliases:
                reg_entry = registry_by_key.get(alias)
                if reg_entry is not None:
                    break

        if reg_entry is None:
            missing.append(
                {
                    "key": kv.key,
                    "version": kv.version,
                    "missingReasons": [{"source": "registry_doc", "reason": "not_found"}],
                }
            )
            continue

        configurations = reg_entry.get("configurations", [])
        if not isinstance(configurations, list):
            configurations = []

        chosen = pick_registry_configuration_record(
            configurations, supported_version=kv.version, lang=args.lang
        )

        if chosen is None:
            missing.append(
                {
                    "key": kv.key,
                    "version": kv.version,
                    "missingReasons": [{"source": "registry_doc", "reason": "not_found"}],
                }
            )
            continue

        desc_any = chosen.get("description")
        if not is_nonempty_description(desc_any):
            missing.append(
                {
                    "key": kv.key,
                    "version": kv.version,
                    "missingReasons": [{"source": "registry_doc", "reason": "quality"}],
                }
            )
            continue

        desc = str(desc_any).strip()
        if not passes_quality_bar(desc):
            missing.append(
                {
                    "key": kv.key,
                    "version": kv.version,
                    "missingReasons": [{"source": "registry_doc", "reason": "quality"}],
                }
            )
            continue

        documented.append(
            {
                "key": kv.key,
                "version": kv.version,
                "results": [
                    {
                        "description": desc,
                        "shortDescription": "",
                        "source": "registry_doc",
                    }
                ],
            }
        )

    stable_sort_key_version(documented)
    stable_sort_key_version(missing)

    output_obj = {
        "lang": args.lang,
        "missingCount": len(missing),
        "documentedCount": len(documented),
        "documentedConfigurations": documented,
        "missingConfigurations": missing,
    }

    out_path = out_dir / "configurations_descriptions_step_1_extracted.json"
    write_json_file(out_path, output_obj)

    eprint(
        f"[step1] wrote {out_path} (documented={len(documented)} missing={len(missing)})"
    )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())


