# Hotness

For each changed method, decide *which multiplier applies* before flagging anything.
**Scope**: the diff × known hot paths. Don't review the world.

## Hotness rubric

The key question when reviewing any code: *is this on a hot path?*

The default answer is yes. The burden of proof runs toward cold. Ask "is there evidence this is
cold or guarded?" — not "is there evidence this is hot?" (that rationalizes itself into "probably
not").

**Hot anchors.** Paths are hot when reachable from:
- `@Advice.OnMethodEnter` / `@Advice.OnMethodExit` (per-advised-call)
- Per-span or per-trace callbacks
- Request or message handlers
- Streaming chunk handlers (even if the overall stream is slow — see Domain adjustment below)

**Cold only with positive evidence.** A path is cold if it is: a one-time init, a startup-only
path, a genuinely rare error branch, or behind a guard that provably fires rarely. There are
other exceptions — schedulers, startup code, I/O-heavy paths — but the default is: *assume hot
unless there is positive evidence of cold*.

**Watch for the interprocedural trap.** Hot entry points are often indirect. A method buried
three helpers deep from an `@Advice` entry is still hot. Trace up before assuming cold.

## Hot-path map (where cost matters — and the multiplier)

Span lifecycle (create / setTag / finish), tag map ops, serialization/encoding, the metrics/stats
path, decorators, propagation (header read/write). **Multiplier: per-span × spans/request ×
requests/sec.** A per-span cost is multiplied massively; a per-process/once cost is negligible.
The AI must reason about *which multiplier applies* before flagging.

## Domain adjustment

**Large-denominator domains.** LLMObs, CI Visibility, DJM instrument large units of work (LLM
calls 500 ms+, Spark jobs seconds–minutes, CI test steps milliseconds–minutes). Per-"span"
CPU/alloc severity collapses. But the risk *inverts*: payload memory (large prompts, job metadata,
accumulated output) becomes SEV-1. A CPU-weighted reviewer flags the wrong things and misses the
real one.

**Not DSM, and not a product that's off.** DSM runs on per-message paths, where a Kafka consumer
does about 0.7 µs of its own work per record, so it gets no relief. Neither does work done for any
product while it's off: every customer who doesn't use it pays (`checks/product-off.md`, J14).

**Streaming handlers — large-denominator rule suspends at the chunk level.** The per-call
denominator applies to costs that fire once per call. Costs inside a streaming handler fire
per-chunk — SSE, chunked HTTP, gRPC streaming can produce hundreds of events per response. An
unbounded accumulator inside a streaming handler (buffering all chunks until stream close) is
SEV-1 regardless of how slow the overall stream is.

**AppSec sub-domain split.** The WAF blocking path fires only when a block action is triggered —
genuinely cold, SILENT. The IAST taint/sink Reporter can fire frequently during an active security
scan. Treat stream usage and per-call allocations on the IAST Reporter path as SOFT-ALERT, not
cold. Do not apply "AppSec = cold" uniformly across AppSec sub-products.

*Examples:*
- An LLMObs 5 MB mapper buffer. Same "big buffer" shape as the oversized `StringBuilder`
  (`checks/repeat-work.md`), *opposite verdict*: the large-denominator (500 ms+ LLM call) absorbs
  the cost. The right call was to accept it.
- An LLMObs streaming helper that accumulated all SSE chunks into an `ArrayList` held
  until stream close. The large-denominator rule would have suppressed this; the chunk-level
  carve-out catches it: SEV-1, regardless of stream duration.
