Override for `reviewers/design.md` (in the core skill folder) — read that file first, then this.

# Design — dd-trace-java specifics

## Module map and layer boundaries

Start at **ARCHITECTURE.md § "Codemap"** — it owns the module boundaries and what belongs where; do not restate it from memory. Layering rules it states explicitly, in scope for this lens:

- `dd-trace-core` and `internal-api` "grew organically" and now host multi-product code beyond their original scope. Genuinely product-*agnostic* infrastructure being pulled out of either belongs in `components/`; product-*specific* implementation belongs in `products/`. A new file added to either just because "that's where similar code already lives" is the duplication-of-drift this lens should catch.
- `components/` must stay bootstrap-safe, dependency-free, and product-agnostic (see ARCHITECTURE.md § "components/"). A new dependency, or a product-specific type, landing there is a shape violation.
- `products/` modules typically follow the `{product}-api` / `{product}-bootstrap` / `{product}-lib` / `{product}-agent` layering, but no existing product implements it exactly: `metrics` has no `-bootstrap`; `feature-flagging` adds an extra `-config` submodule. Don't flag a missing or extra submodule name against this list — the layering shape is aspirational, not enumerable. What *is* a hard rule regardless of which submodules a product has: implementation weight added to a thin/boundary submodule (`-api`, `-bootstrap`, `-config`) instead of `-lib` is a layer violation, not a style choice.

## Public API surface

This repo's public API lives in `dd-trace-api/` (`Tracer`, `GlobalTracer`, `DDTags`, `DDSpanTypes`, the `@Trace` annotation, the `*Config` constant classes) and in `dd-trace-ot/`'s `io.opentracing.Tracer` implementation — see ARCHITECTURE.md § "dd-trace-api/" and § "dd-trace-ot/". A change adding a class or method to either is public surface and needs explicit justification; it is forever. `internal-api/` is internal despite the name — it's fair game to reshape, but check callers across `products/` and `dd-java-agent/` before calling a change there "just internal."

## Configuration surface

Read **docs/add_new_configurations.md** — it owns the registration steps; check the diff against it, don't restate it here. One design-shaped consequence that doc doesn't state: `internal-api`'s split between `Config` and `InstrumenterConfig` exists for a build-time reason, not convenience — GraalVM native-image builds freeze instrumentation-affecting decisions into the binary at build time, so a setting that controls which classes/integrations get instrumented belongs in `InstrumenterConfig`; a setting that's runtime-only (endpoints, service name, sampling rate) belongs in `Config` (see ARCHITECTURE.md § "internal-api/"). Landing a native-image-relevant setting in the wrong one breaks native-image builds silently — flag it even if the config-registration mechanics (which belongs to the conventions lens) are otherwise followed correctly.

## Extension points (instrumentations)

An instrumentation must go through `InstrumenterModule` + the `Instrumenter` type-matching interfaces (`ForSingleType`, `ForKnownTypes`, `ForTypeHierarchy`, `ForBootstrap`) and be discovered via `@AutoService(InstrumenterModule.class)` — see ARCHITECTURE.md § "agent-tooling/" and **docs/add_new_instrumentation.md** / **docs/how_instrumentations_work.md**. A bespoke `ClassFileTransformer` or advice registered outside this mechanism bypasses Muzzle's build-time version-safety checks entirely — that's a P0 shape problem, not a nit, independent of whether the bespoke code works.

## Lifecycle / bootstrap

The bootstrap and advice correctness rules for this code live in **AGENTS.md § "Critical constraints"** and **docs/bootstrap_design_guidelines.md** / **docs/instrumentation_design_guidelines.md** — this lens owns them; do not restate them from memory, open the doc and check the diff against it. (The performance override's "Bootstrap / startup-latency note" covers the same code from the cost angle — that's a different finding on the same lines, not a duplicate.) Respect the ordering in ARCHITECTURE.md § "Startup Sequence": `AgentBootstrap.premain()` must stay tiny and side-effect-free; anything heavier belongs in `Agent.start()` or a product's own `*System.start()`, never in premain-reachable code.

## Cross-cutting mechanisms already in the repo

Before approving a new cross-cutting abstraction, check whether one already exists — see ARCHITECTURE.md § "internal-api/":

- `gateway/` — the Instrumentation Gateway event bus. AppSec and IAST use it to hook the HTTP request lifecycle *without* touching instrumentations directly. A new instrumentation reaching into AppSec/IAST internals directly, instead of publishing through the gateway, is a layering violation.
- `cache/` — `DDCache`, `FixedSizeCache`, `RadixTreeCache`.
- `naming/` — span/service naming schemas (v0, v1).

A second bespoke event bus, cache, or naming scheme is a P1 duplication finding at minimum, per the generic file's "Duplication of an existing mechanism" check.

## Not this lens's job

- Config-registration file mechanics (`supported-configurations.json`, the CI validator) — conventions lens.
- Allocation cost, hot-path multipliers, or JIT behavior of a given shape — performance lens.
- Instrumentation package/class naming and Gradle layout mechanics — conventions lens (the same docs are cited there too; this file only owns whether the extension *mechanism* chosen is the right one, not how it's named or laid out).
