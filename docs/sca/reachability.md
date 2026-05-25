# SCA Reachability — Architecture and Invariants

Design constraints and non-obvious rules for the SCA Reachability feature.

## Three-condition gate (fundamental invariant)

A class appearing in `sca_cves.json` is NOT reported just because it is in the index. All three
conditions must hold:

1. Class is in the index (`database.entriesForClass()` returns entries)
2. Artifact is loaded: `DependencyResolver.resolve(jarUrl)` returns a `Dependency` with
   `name == entry.artifact()`
3. Version is vulnerable: `entry.isVersionVulnerable(dep.version)` is true

The index exists for O(1) lookup. Artifact + version filtering always runs in `processClass()`.
Classes in `pendingRetransform` still pass through the full filter -- if the version has not yet
resolved, `transform()` returns `null` without reporting.

## `ClassFileTransformer` contract

- `className` in `transform()` uses internal slash format: `"com/foo/Bar"`. The lookup map must
  use this format as its key.
- Return `null` to leave bytecode unmodified (correct). Do not return the original buffer.
- `protectionDomain` may be null (bootstrap classes). `protectionDomain.getCodeSource()` may also
  be null (runtime-generated classes). Both null checks are mandatory.
- `transform()` is called concurrently -- use immutable or concurrent data structures.
- Register with `addTransformer(transformer, true)` to allow `retransformClasses()`.

## Two periodic actions and their relationship

**`DependencyPeriodicAction`** (always active): drains `DependencyService` and reports ALL detected
JARs. With SCA enabled it emits `metadata:[]` on each dep as a signal that SCA is monitoring.
This is the complete inventory -- it knows nothing about CVEs.

**`ScaReachabilityPeriodicAction`** (only when SCA enabled): drains `ScaReachabilityDependencyRegistry`
and re-emits only deps with pending CVE state, with `metadata:[{id, reached:[...]}]`.

**Duplicate invariant**: within the same heartbeat window, the same dependency may arrive twice:

1. From `DependencyPeriodicAction`: `{snakeyaml, metadata:[]}` -- dep just detected
2. From `ScaReachabilityPeriodicAction`: `{snakeyaml, metadata:[{cve, reached:[callsite]}]}` -- hit registered

This happens when a dep is detected AND has a CVE hit in the same heartbeat window (common with
the 60s default). The backend must merge by `name:version`, taking the richer entry. This is not
a bug.

## Version matching

`ComparableVersion` in `internal-api/src/main/java/datadog/trace/util/ComparableVersion.java` is
a backport of Apache Maven 3.9.9. It supports 4-part versions and pre-release qualifiers.

- `isWithin(start, end)` is half-open: `[start, end)` -- inclusive start, exclusive end.
- GHSA range formats: `"< 2.6.7.3"`, `">= 2.7.0, < 2.7.9.5"`, `"= 9.5.0"`.
- `< X` → `compareTo(X) < 0`. `= X` → `compareTo(X) == 0`. `>= A, < B` → `isWithin(A, B)`.

## Class-level vs method-level symbols

**Class-level (`method: null`)**: the class load IS the reachability signal. Use only when no
specific method is instrumentable or when class presence in memory is sufficient.

**Risk with mixed class-level + method-level in the same entry**: if the same CVE entry has both,
the class-level hit registers `<clinit>` in the registry and first-hit-wins blocks subsequent
method-level hits. Result: `reached=[{<clinit>}]` instead of the application callsite.

**Rule**: for libraries that load at startup (snakeyaml, xstream), remove the class-level symbol
and keep only method-level symbols. The class load triggers `registerCve()` with `reached:[]`
and the subsequent method call records the correct callsite.

## Startup wiring

- `AppSecSystem.start()` does NOT receive `Instrumentation`. Only `SubscriptionService` and
  `SharedCommunicationObjects` are passed in.
- The correct pattern is a separate `maybeStartScaReachability(Instrumentation, ...)` method in
  `Agent.java`, gated by `Config.get().isAppSecScaEnabled()`, called after `maybeStartAppSec()`.

## Hard constraints (never violate)

- Never use `java.nio.*` in premain code -- this includes `StandardCharsets.UTF_8`. Use the
  string literal `"UTF-8"` instead. See `docs/bootstrap_design_guidelines.md`.
- Never modify bytecode for class-level hits (always `return null`). For method-level hits,
  bytecode modification is required.
- Never throw from `transform()`.
- Never do blocking I/O in the `transform()` hot path. Use a
  `ConcurrentHashMap<URL, List<Dependency>>` cache. Do not cache empty results.
- Never report the same `(vulnId, artifact, symbolName)` more than once. Dedup: class-level in
  the transformer, method-level in bootstrap `ScaReachabilityCallback.reported`.
- Never write to spans or traces -- the RFC explicitly forbids it.
- Never use `String.split(String)` or `String.split(String, int)` (forbidden API). Use a
  `static final Pattern` field with `Pattern.compile(...).split(str)`.

## GHSA data format

- Each file is a JSON array: `[{...}]`. Process only `"language": "jvm"` entries.
- Full class name = `value + "." + name` from `ecosystem_specific.imports[].symbols[]`.
- Version ranges in `package[].version_range[]` (array of strings, treated as OR).
- The GHSA ID is the identifier -- extracted from the filename (`GHSA-xxx.json` → `"GHSA-xxx"`).
  No CVE ID is present in the file.

## Telemetry serialization

- `Dependency` is a `public final class` with exactly 4 fields: `name`, `version`, `source`,
  `hash` + optional `reachabilityMetadata: List<String>` (nullable). `source` is NOT serialized.
- Serialization uses **Moshi `JsonWriter`** in `TelemetryRequestBody.writeDependency()`. Not Jackson.
- `null` metadata = SCA disabled (field omitted in JSON). `[]` = SCA active, no CVEs. `[{...}]` = CVE state.
- The `metadata.value` field in the RFC MUST be a JSON string (not an object) -- stringify explicitly.
