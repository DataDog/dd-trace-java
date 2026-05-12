#!/usr/bin/env python3
"""
Pure-Python interactive SVG flamegraph generator.
Reads folded-stack format from a file or stdin, writes an SVG file.

Folded-stack format (same as flamegraph.pl):
  root;child;...;leaf count
  e.g. "java.lang.Thread.run;overhead.Workload.main;ch.qos.logback.Logger.info 42"

Usage:
  python3 flamegraph.py [input.folded | -] output.svg [--title "My Title"] [--width 1200]
"""
import sys, collections, html, re, hashlib

# ---------- layout constants ----------
FRAME_H = 16      # px per frame row
PAD_TOP = 70      # px above the first frame row
PAD_BOT = 10
PAD_SIDE = 10
MIN_WIDTH_PX = 0.3   # frames narrower than this are skipped

# ---------- colour by package prefix ----------
def _color(name: str) -> str:
    h = int(hashlib.md5(name.encode()).hexdigest()[:4], 16)
    if name.startswith(("datadog.", "com.datadog.")):
        return f"rgb({205+h%40},{60+h%40},{60+h%40})"   # red
    if name.startswith(("ch.qos.logback.", "org.slf4j.", "ch.qos.")):
        return f"rgb(240,{140+h%50},{60+h%30})"          # orange
    if name.startswith("net.bytebuddy."):
        return f"rgb({160+h%40},{60+h%30},{210+h%40})"   # purple
    if name.startswith(("java.", "sun.", "jdk.", "javax.")):
        return f"rgb({60+h%40},{170+h%50},{60+h%40})"    # green
    if name.startswith(("io.opentracing.", "com.sun.")):
        return f"rgb({80+h%40},{140+h%50},{200+h%40})"   # blue
    return f"rgb({210+h%30},{190+h%30},{60+h%40})"       # yellow


# ---------- tree building ----------
class _Node:
    __slots__ = ("name", "total", "self_v", "children")

    def __init__(self, name: str) -> None:
        self.name = name
        self.total = 0
        self.self_v = 0
        self.children: dict[str, "_Node"] = {}


def _build_tree(folded: dict[str, int]) -> _Node:
    root = _Node("")
    for stack, count in folded.items():
        frames = stack.split(";")
        node = root
        node.total += count
        for f in frames:
            if f not in node.children:
                node.children[f] = _Node(f)
            node = node.children[f]
            node.total += count
        node.self_v += count
    return root


# ---------- layout pass ----------
def _layout(node: _Node, depth: int, x: float, avail_w: float,
            total_root: int, out: list) -> int:
    """Recursively lay out frames; returns max depth seen."""
    if avail_w < MIN_WIDTH_PX:
        return depth
    out.append((node.name, depth, x, avail_w,
                 node.total, node.self_v, total_root))
    max_d = depth
    child_x = x
    for child in node.children.values():
        child_w = avail_w * child.total / node.total if node.total else 0
        max_d = max(max_d, _layout(child, depth + 1, child_x, child_w,
                                   total_root, out))
        child_x += child_w
    return max_d


# ---------- SVG rendering ----------
_JS = r"""
var currentZoom = null;
function tipShow(evt, txt) {
  var t = document.getElementById('tip');
  t.textContent = txt;
  t.setAttribute('x', Math.min(evt.clientX + 10, +t.ownerSVGElement.getAttribute('width') - 300));
  t.setAttribute('y', 54);
  t.style.display = 'block';
}
function tipHide() { document.getElementById('tip').style.display = 'none'; }
function zoomTo(el) {
  var name = el.getAttribute('data-n');
  if (name === currentZoom) {
    currentZoom = null;
    document.querySelectorAll('rect[data-n]').forEach(function(r) {
      r.style.opacity = '1';
    });
    return;
  }
  currentZoom = name;
  document.querySelectorAll('rect[data-n]').forEach(function(r) {
    r.style.opacity = (r.getAttribute('data-n') === name) ? '1' : '0.25';
  });
}
"""


def render_svg(title: str, folded: dict[str, int], width: int = 1200) -> str:
    root = _build_tree(folded)
    total = root.total
    if total == 0:
        return ""

    avail_w = width - 2 * PAD_SIDE
    frames: list = []
    max_depth = _layout(root, 0, 0.0, avail_w, total, frames)

    height = PAD_TOP + (max_depth + 1) * FRAME_H + PAD_BOT

    rect_lines = []
    for name, depth, x, w, tot, sv, total_root in frames:
        rx = PAD_SIDE + x
        ry = PAD_TOP + (max_depth - depth) * FRAME_H
        rw = max(w - 1, 0)
        rh = FRAME_H - 1
        pct = 100.0 * tot / total_root if total_root else 0
        fill = _color(name) if name else "rgb(220,220,220)"
        short = name.split(".")[-1].split("$")[-1] if name else "(all)"
        esc_n = html.escape(name or "(all)", quote=True)
        tip_txt = html.escape(
            f"{name or '(all)'}  |  {tot} samples ({pct:.1f}%)"
            + (f"  self={sv}" if sv else ""),
            quote=True,
        )
        # text: truncate to fit cell
        max_chars = max(0, int(rw / 7))
        lbl = html.escape(short[:max_chars]) if rw > 20 else ""
        rect_lines.append(
            f'<rect x="{rx:.2f}" y="{ry}" width="{rw:.2f}" height="{rh}" '
            f'fill="{fill}" rx="1" '
            f'data-n="{esc_n}" '
            f'onmouseover="tipShow(event,\'{tip_txt}\')" '
            f'onmouseout="tipHide()" '
            f'onclick="zoomTo(this)"/>'
        )
        if lbl:
            rect_lines.append(
                f'<text x="{rx+3:.2f}" y="{ry+11}" '
                f'font-size="11" fill="#111" pointer-events="none">{lbl}</text>'
            )

    rects_block = "\n  ".join(rect_lines)
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" version="1.1"
     width="{width}" height="{height}">
 <style>
  text {{ font-family: monospace; }}
  rect[data-n]:hover {{ stroke: #333; stroke-width: 1; cursor: pointer; }}
 </style>
 <script><![CDATA[
{_JS}
 ]]></script>
 <!-- header -->
 <text x="{width//2}" y="26" text-anchor="middle"
       font-size="18" font-weight="bold">{html.escape(title)}</text>
 <text x="10" y="46" font-size="12" fill="#888">{total} samples total</text>
 <text x="{width//2}" y="46" text-anchor="middle"
       font-size="12" fill="#555">Click a frame to highlight · click again to reset</text>
 <!-- frames -->
 {rects_block}
 <!-- tooltip -->
 <text id="tip" x="0" y="0" font-size="12" fill="#000"
       style="display:none;background:#fff;"/>
</svg>
"""


# ---------- folded-stack extraction from JFR print text ----------
def jfr_text_to_folded(lines: list[str]) -> dict[str, int]:
    """
    Parse 'jfr print --events ExecutionSample|ObjectAllocationInNewTLAB|...' text output
    and return folded-stack counts.

    JFR print stacks are innermost (leaf) first, outermost (root) last.
    We reverse them so the folded format is root;...;leaf.
    """
    folded: dict[str, int] = collections.Counter()
    frame_re = re.compile(r"^\s{4,}([\w.$<>]+\.[\w$<>]+)\(")
    in_stack = False
    cur: list[str] = []

    for line in lines:
        if "stackTrace = [" in line:
            in_stack = True
            cur = []
            continue
        if in_stack:
            stripped = line.strip()
            if stripped == "]":
                if cur:
                    # cur is leaf→root; reverse to get root→leaf for flamegraph
                    folded[";".join(reversed(cur))] += 1
                cur = []
                in_stack = False
                continue
            m = frame_re.match(line)
            if m:
                cur.append(m.group(1))
    return dict(folded)


# ---------- CLI ----------
def main(argv: list[str] = None) -> None:
    if argv is None:
        argv = sys.argv[1:]

    title = "Flamegraph"
    width = 1200
    infile = None
    outfile = None
    i = 0
    while i < len(argv):
        if argv[i] in ("--title", "-t") and i + 1 < len(argv):
            title = argv[i + 1]; i += 2
        elif argv[i] in ("--width", "-w") and i + 1 < len(argv):
            width = int(argv[i + 1]); i += 2
        elif argv[i] in ("--jfr-text",):
            # hint that input is raw jfr print text (not folded)
            # (default behaviour since we auto-detect below)
            i += 1
        elif argv[i].endswith(".svg"):
            outfile = argv[i]; i += 1
        else:
            infile = argv[i]; i += 1

    if outfile is None:
        print("Usage: flamegraph.py [input | -] output.svg [--title 'Title'] [--width N]",
              file=sys.stderr)
        sys.exit(1)

    if infile is None or infile == "-":
        raw = sys.stdin.readlines()
    else:
        with open(infile) as fh:
            raw = fh.readlines()

    # Auto-detect format: folded lines look like "a;b;c N"; JFR text has "stackTrace ="
    is_jfr_text = any("stackTrace = [" in l for l in raw[:50])
    if is_jfr_text:
        folded = jfr_text_to_folded(raw)
    else:
        # standard folded format
        folded = {}
        for line in raw:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.rsplit(" ", 1)
            if len(parts) == 2:
                try:
                    folded[parts[0]] = folded.get(parts[0], 0) + int(parts[1])
                except ValueError:
                    pass

    if not folded:
        print(f"Warning: no stacks found in input — {outfile} not written.", file=sys.stderr)
        sys.exit(0)

    svg = render_svg(title, folded, width)
    with open(outfile, "w") as fh:
        fh.write(svg)
    print(f"Written: {outfile}  ({len(svg)} bytes)", file=sys.stderr)


if __name__ == "__main__":
    main()
