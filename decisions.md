# Decisions

Design decisions made during this session — the "why A over B" reasoning.
Injected into context on every user message. Propagated to feature manifest at PR open.

## Metis adversarial review (Paso 4.9, 2026-09-17)

Findings that revise the draft plan before the checkpoint:

- BLOCKING: AppSec-only default path must AND in the ddprof environment-safety predicate
  (`!isDatadogProfilerEnablementOverridden() && isDatadogProfilerSafeInCurrentEnvironment()
  && !Platform.isNativeImage()`), not just `!OperatingSystem.isWindows()` — otherwise ships
  native-image/J9/JDK8-aarch64 crash-class regressions. `isDatadogProfilerEnabled()` is NOT a
  usable "raw check" (it already ANDs `isProfilingEnabled()`); need a new raw getter.
- Test rule: no `ConfigTest.groovy` case — project CLAUDE.md mandates JUnit 5 Java for new tests.
- `ProcessContext.register()` double-invocation is pre-existing behavior today (via
  `ProfilingAgent.run()`'s documented reentrancy for early-start), not an open question — do not
  add a naive `AtomicBoolean` guard without confirming the second call is redundant first.
- New `createProfilingContextIntegration()` call-site placement (`installDatadogTracer`, called
  from 2 places, "can be called multiple times") risks its own multi-invocation + moves native
  lib load/hostname resolution earlier into premain — unvalidated, needs explicit handling.
- Two-way interaction gaps to resolve explicitly: (a) explicit `DD_PROFILING_DDPROF_ENABLED=false`
  must still veto even when AppSec triggers the new flag; (b) new flag = false for a profiling
  user must not silently fall through to JFR/NoOp and drop ddprof context labels.
- Naming: `TRACE_OTEL_CONTEXT_ENABLED`/`DD_TRACE_OTEL_CTX_ENABLED`/`isOtelContextPropagationEnabled()`
  are inconsistent ("propagation" is the wrong word - this is exposure, not propagation). Also:
  computed default (false for plain tracing) diverges from dd-trace-py's default-true semantics
  under the same env var name - decide explicitly, don't inherit accidentally.
- `_dd.profiling.ctz` tag will now appear on AppSec-only users' spans with no profile behind it -
  explicit accept/reject decision needed, not a footnote.
- Step 9 (system-tests smoke run) is not executable on this darwin dev machine (ddprof is
  Linux-only) - must be reframed as a CI/Linux-host step.
- Step 7 must add `spotlessApply`/`spotlessCheck` and exercise the config-inversion check
  (`ConfigInversionExtension`) for the new metadata entry.

## Cross-tracer precedent added by user (checkpoint, 2026-09-17)

User: PHP tracer's equivalent is "automatically enabled when `DD_APPSEC_ENABLED=true`" — a plain
boolean, not an activation-level nuance (PHP has no FULLY_ENABLED/ENABLED_INACTIVE distinction).
Closest Java analog to a bare "AppSec is on" boolean is `ProductActivation.FULLY_ENABLED`
(`ENABLED_INACTIVE` is the Java/dd-trace-java-specific "could be remote-config-activated later"
state PHP doesn't model) — this supports the FULLY_ENABLED-only trigger level already proposed
via the `traceResourceRenamingEnabled` precedent, now with two independent cross-tracer votes
(dd-trace-py's decoupled flag + PHP's AppSec-boolean trigger).

## Checkpoint decisions confirmed by user (2026-09-17)

1. **AppSec trigger level: `ProductActivation.FULLY_ENABLED` only** (cross-tracer precedent:
   dd-trace-py's decoupled flag + PHP's `DD_APPSEC_ENABLED=true` boolean trigger).
2. **Explicit `DD_PROFILING_DDPROF_ENABLED=false` vetoes ddprof integration even when AppSec would
   otherwise trigger the new flag.** The new AppSec-only default path must still respect an
   explicit false on the ddprof raw flag.
3. **The new flag is additive (OR), never a replacement gate for existing profiling users.**
   `isProfilingEnabled() && isDatadogProfilerEnabled()` remains sufficient on its own; the new
   flag only adds the AppSec-only path. Setting the new flag to false must NOT disable ddprof for
   a user where real profiling already enabled it (no silent JFR/NoOp fallthrough for profiling
   users).
4. **`_dd.profiling.ctx` appearing on AppSec-only users' spans is accepted as-is** — pre-existing
   side effect of instantiating `DatadogProfilingIntegration`, not worth special-casing.
5. **Naming**: `OtlpConfig.TRACE_OTEL_CONTEXT_EXPOSURE_ENABLED = "trace.otel.context-exposure.enabled"`,
   env `DD_TRACE_OTEL_CONTEXT_EXPOSURE_ENABLED`, getter `Config.isOtelContextExposureEnabled()`.
   Deliberately NOT reusing dd-trace-py's `DD_TRACE_OTEL_CTX_ENABLED` name, since the Java default
   is conditional (AppSec-or-profiling-driven) vs Python's unconditional default-true — same name
   with different semantics would be a cross-tracer trap.
6. **Config file placement**: `OtlpConfig.java` (groups with existing `TRACE_OTEL_ENABLED`).
7. **Call-site placement**: `ProcessContext.register()` for the AppSec-only path is added inside
   `Agent.createProfilingContextIntegration()` (same single wiring point as the rest of the ddprof
   branch), NOT in `installDatadogTracer()` — avoids the multi-invocation and premain-timing risks
   Metis flagged for that call site.

## Final gating design (synthesized from decisions 1-4, resolves Metis findings 1-2)

New raw getter `Config.isDatadogProfilerSafeAndConfigured()` = the existing raw
`isDatadogProfilerEnabled` field logic (env-safety predicate + explicit-flag respect, native-image
excluded) WITHOUT the `isProfilingEnabled()` AND-prefix — i.e., exactly today's private field
expression, now exposed on its own.

`isOtelContextExposureEnabled()` = explicit override via `configProvider.getBoolean(...)` if set,
else:
```
isDatadogProfilerSafeAndConfigured()
    && (isProfilingEnabled() || getAppSecActivation() == ProductActivation.FULLY_ENABLED)
```
This is additive/OR (decision 3: never disables ddprof for existing profiling users), respects an
explicit `DD_PROFILING_DDPROF_ENABLED=false` (decision 2: baked into the raw predicate), and never
bypasses the native-image/env-safety exclusions (fixes Metis finding 1).

## Idempotency verified with evidence (resolves Metis finding 4, 2026-09-17)

Read `com/datadoghq/profiler/OTelContext.java` directly from the ddprof sources jar
(`~/.gradle/caches/modules-2/files-2.1/com.datadoghq/ddprof/1.50.0/.../ddprof-1.50.0-sources.jar`).
`initializeAllContext()`'s own Javadoc states: "Calling this method multiple times will replace
the previous context with the new values" — confirmed idempotent/reentrant by design, additionally
guarded internally by a `ReentrantReadWriteLock` write lock around the native `setProcessCtx0`
call. **No `AtomicBoolean` or other double-invocation guard is needed** in `ProcessContext.register()`
or at the new AppSec-only call site.

## Remaining scope decisions (2026-09-17)

- System-tests `THREAD_CONTEXT_SHARING` validation: **out of this PR**, documented as a manual/CI
  follow-up (command + preconditions), not tracked as an executable TODO in `task_plan.md` (not
  runnable on this darwin dev machine; ddprof is Linux-only).

## TODO-10 — /techdebt overreach reverted (2026-09-17)

The `/techdebt` review agent removed `Config.isDatadogProfilerSafeAndConfigured()` (added in
TODO-2) and inlined the raw `isDatadogProfilerEnabled` field at its single call site, reasoning it
was an unnecessary one-use abstraction. This directly undoes an explicit, adversarially-reviewed
design decision (see `.claude-invariants.md` invariant #8 and "Final gating design" above): the
getter exists specifically so any *future* AppSec-only trigger path reuses the raw ddprof
env-safety predicate correctly, without falling back to `isDatadogProfilerEnabled()` (which already
ANDs `isProfilingEnabled()` and would silently reintroduce the native-image/J9/JDK8 regression
Metis flagged). The `/techdebt` agent had no access to this task's `.claude-invariants.md`/
`decisions.md` context, so it could not see why the getter was deliberate rather than incidental.

**Resolution:** reverted the removal — restored `isDatadogProfilerSafeAndConfigured()` with an
expanded Javadoc explaining the reuse rationale, and restored its use in the `otelContextExposureEnabled`
computation. Recompiled and re-ran `:internal-api:test`/`spotlessApply`/`spotlessCheck` — all pass,
no behavior change (the inlined and getter-based forms were computationally identical; only the
discoverability/reuse-safety property was at stake).

## TODO-11 — Manual/CI follow-up: `THREAD_CONTEXT_SHARING` system-tests validation (2026-09-17)

Out of scope for this PR (invariant #20): cannot be executed from this darwin dev machine, since
ddprof and the eBPF/system-probe consumer are Linux-only. Documented here as a follow-up step for
whoever validates this change on a Linux host / in CI, not tracked as an in-repo executable TODO.

**Scenario:** `tests/cws/test_thread_context_sharing.py::Test_ThreadContextSharing`
(`THREAD_CONTEXT_SHARING` scenario in `system-tests`, introduced in system-tests PR #7617).

**What it validates:** that a JVM running with this change's new AppSec-only trigger path
(`Config.isOtelContextExposureEnabled()` true via `ProductActivation.FULLY_ENABLED` with profiling
disabled) exposes both the thread-local span context (native TLS write via
`DatadogProfilingIntegration`) and the process-wide descriptor (`ProcessContext.register()` ->
`OTelContext.initializeAllContext(...)`) so that an eBPF/CWS consumer running alongside the JVM can
read the current span context off a traced thread.

**Preconditions:**
- Linux host (the ddprof native library and the eBPF/system-probe consumer are Linux-only; this
  scenario cannot run on macOS/darwin).
- Datadog Agent >= 7.84.0-devel (the version that ships the eBPF/CWS-side consumer for this
  context-sharing mechanism).
- `DD_RUNTIME_SECURITY_CONFIG_ENABLED=true` (or the scenario's documented equivalent) on the traced
  JVM's Agent, so the Datadog Agent's system-probe/eBPF component is active.
- System-probe/eBPF support available in the test environment (typically requires elevated
  privileges / a kernel with the needed eBPF features — see `system-tests`' own scenario
  preconditions for `THREAD_CONTEXT_SHARING` in `utils/_context/_scenarios/__init__.py`).

**How to run (on a Linux host with system-tests set up, from the `system-tests` repo root):**
```bash
./run.sh THREAD_CONTEXT_SHARING
```
(Standard system-tests scenario invocation; substitute the dd-trace-java build under test per the
system-tests library-injection instructions if validating this branch specifically, e.g. via a
locally built `dd-java-agent` jar per `docs/how_to_smoke_test.md`/system-tests' Java onboarding
docs.)

**Specific assertion this PR's change should newly satisfy:** with AppSec `FULLY_ENABLED` and
profiling disabled, the scenario's checks for both thread-context (TLS) and process-context
(`OTelContext` descriptor) presence should now pass, where before this change they would have been
absent (both gates were previously collapsed into `isProfilingEnabled()`, per invariant #2).

## Premain-timing fix for the AppSec-only ddprof path (Codex P1 on PR #12546, 2026-09-17)

**Problem (Codex review, inline on `Agent.java:1506`):** with AppSec `FULLY_ENABLED` and profiling
disabled, the widened ddprof branch is reached from `InstallDatadogTracerCallback.execute()`, i.e.
on the JVM's primordial premain thread. Constructing `DatadogProfilingIntegration` initializes
`DatadogProfiler`, whose constructor goes through `TempLocationManager` and `java.nio.file.Files`,
which can lock in the default filesystem provider before the application configures one in `main`
— a direct violation of invariant #11 / `AGENTS.md`'s bootstrap constraints. This premain-time NIO
touch already exists today for real-profiling users (accepted, pre-existing), but this PR extended
it to a brand-new population that previously never loaded ddprof that early.

**Design chosen:** new package-private wrapper
`dd-java-agent/agent-bootstrap/.../DeferredProfilingContextIntegration` implementing
`ProfilingContextIntegration`. It is returned synchronously (the caller needs a non-null integration
immediately) while the real reflective construction of `DatadogProfilingIntegration` *and* the
subsequent `ProcessContext.register(ConfigProvider)` call run on `AgentTaskScheduler.get().execute(...)`
— the same "defer past premain" primitive already used by `startCrashTracking()`. Until the swap it
delegates to `ProfilingContextIntegration.NoOp.INSTANCE` via a single `volatile` delegate field
(volatile, not `AtomicReference`: a plain publish/read is all the swap needs and it matches the
existing lazy-publish style in this module); after the swap every interface method delegates to the
real integration. A failing deferred construction logs at `log.debug(...)` and leaves the instance
behaving as `NoOp` forever — a background task never propagates a failure. No idempotency guard was
added around `ProcessContext.register()` (invariant #14 still holds).

**Why the real-profiling path is untouched:** dropping the first few scope events is fine for
context *exposure* (eBPF/CWS reads whatever the current span is when it looks) but not for profiling
accuracy, and "no behavior change for existing profiling users" is a hard constraint of this PR. So
the branch selection is `deferInitialization = !config.isDatadogProfilerEnabled()`: when the Datadog
profiler is enabled the construction stays synchronous, byte-for-byte the current behavior; only the
AppSec-only-only trigger (`!isDatadogProfilerEnabled() && isOtelContextExposureEnabled()`) defers.

**Deviations / judgment calls:**
- Extracted the ddprof construction into a package-private `Agent.createDdprofContextIntegration(ClassLoader, boolean)`
  seam (mirroring the existing `Agent.shutdownFeatureFlagging(ClassLoader)` test seam) so the
  deferral is unit-testable with a fake class loader instead of requiring the real native library.
  `null` return means "synchronous construction failed", preserving the existing fall-through to the
  JFR/NoOp branches.
- `name()` returns the constant `"ddprof"` rather than the current delegate's name. `CoreTracer`
  reads it exactly once when the tracer is built (`_dd.profiling.ctx` tag), which may happen before
  the swap; returning the delegate's name would make that tag race between `"none"` and `"ddprof"`.
  Residual risk: if the deferred construction later fails, the tag reads `"ddprof"` optimistically.
- Narrow edge case accepted: for the (unusual) combination of an explicit
  `DD_TRACE_OTEL_CONTEXT_EXPOSURE_ENABLED=true` with profiling enabled but the ddprof raw predicate
  false, a *failing* ddprof construction no longer falls through to the JFR/timeline branch, because
  the failure is now only known after premain. Every other path keeps its current fallback.
- Tests: `DeferredProfilingContextIntegrationTest` (JUnit 5, Java) asserts that with
  `deferInitialization=true` neither the integration constructor nor `ProcessContext.register` runs
  on the calling thread (gated by a latch so the assertion cannot race the scheduler), that they do
  run afterwards on another thread, that `deferInitialization=false` still constructs synchronously
  on the calling thread, and that a failing factory leaves the wrapper NoOp-equivalent.

### Dropped the dedicated `DD_TRACE_OTEL_CONTEXT_EXPOSURE_ENABLED` config flag (2026-09-18)

**Problem statement:** the original design added a new public config
(`OtlpConfig.TRACE_OTEL_CONTEXT_EXPOSURE_ENABLED`) with an explicit-override branch in
`Config.isOtelContextExposureEnabled()`, on top of the derived default. This surfaced a real cost
during review: any new entry in `metadata/supported-configurations.json` needs manual registration
on the external Feature Parity Dashboard (`docs/add_new_configurations.md` Step 8) before the
`config-inversion-local-validation.py` CI job passes - and this was never done, causing a CI failure
on PR #12546.

**Question raised:** did this feature actually need a dedicated flag, or could it be a pure
derivation from the two conditions that already drive it (profiling enabled, AppSec fully enabled)?

**Precedent checked:** `isProfilingEnabled()` itself has no dedicated override for the *composed*
behavior it exposes - it is driven by `DD_PROFILING_ENABLED` (a `ProfilingEnablement` tri-state) with
no separate "disable profiling context integration but keep profiling" escape hatch. A user who wants
ddprof context labeling off already has a kill switch: disable `DD_PROFILING_ENABLED` and disable
`DD_APPSEC_ENABLED` (or drop it to `inactive`). Composing `isOtelContextExposureEnabled()` from those
two existing, independently-overridable flags gives the same practical kill-switch coverage as
`isProfilingEnabled()` has for itself, without introducing a third, unregistered flag.

**Design chosen:** removed `OtlpConfig.TRACE_OTEL_CONTEXT_EXPOSURE_ENABLED` entirely (constant,
`metadata/supported-configurations.json` entry, and the explicit-override branch in `Config.java`).
`isOtelContextExposureEnabled()` is now a pure derivation:
`isDatadogProfilerSafeAndConfigured() && (isProfilingEnabled() || getAppSecActivation() ==
ProductActivation.FULLY_ENABLED)`. No public config added by this PR - the
`config-inversion-local-validation.py` / Feature Parity Dashboard registration problem is moot.

**What was explicitly considered and rejected as a mismatch:** using `isAppSecScaEnabled()`
(`DD_APPSEC_SCA_ENABLED`) as the AppSec signal instead of `getAppSecActivation() ==
ProductActivation.FULLY_ENABLED`. SCA (Software Composition Analysis, dependency/vulnerability
telemetry) is a different product from AppSec's runtime protection (WAF/RASP) and is not what CWS/eBPF
context exposure needs - a user could have AppSec fully protecting requests with SCA off, and would
wrongly lose context exposure under that substitution.

**Trade-off accepted:** no way for a user to say "profiling and/or AppSec FULLY_ENABLED, but I
specifically don't want context exposure" without disabling one of the two underlying features. Judged
acceptable - `isProfilingEnabled()` has the same limitation, and no support/rollback case has come up
that needs finer granularity than that.

**Tests updated:** removed the three `ConfigOtelContextExposureTest` cases that asserted explicit
override behavior (`explicitFalseOverridesConditionsThatWouldEnableIt`,
`explicitTrueOverridesProfilingAndAppSecActivationLevel`,
`explicitTrueDoesNotBypassDatadogProfilerSafetyPredicate`) - that behavior no longer exists. The
remaining derivation tests (`disabledByDefault`, `enabledWhenProfilingIsEnabled`,
`enabledWhenAppSecIsFullyEnabledWithoutProfiling`, `disabledWhenAppSecIsOnlyEnabledInactive`,
`disabledWhenDatadogProfilerIsExplicitlyDisabled`,
`disabledInAnEnvironmentWhereTheDatadogProfilerIsUnsafe`) are unchanged and still pass.

### Why `Agent.java` still reflectively loads `DatadogProfilingIntegration`/`ProcessContext` instead of depending on `agent-profiling` directly (2026-09-18)

Raised during `/pr-deep-review` of `Config.java`: does `isOtelContextExposureEnabled()` truly not
depend on profiling, given `Agent.createProfilingContextIntegration()` still reaches into
`com.datadog.profiling.ddprof.DatadogProfilingIntegration` and
`com.datadog.profiling.agent.ProcessContext` via `AGENT_CLASSLOADER.loadClass(...)`? Anticipated
reviewer question: why not move that code somewhere reusable instead of loading it via reflection.

**Verified:**
- The reflective `loadClass(...)` mechanism predates this PR. On `origin/master`,
  `createProfilingContextIntegration()` already loaded `DatadogProfilingIntegration` this exact way
  for the `isProfilingEnabled() && isDatadogProfilerEnabled()` branch, and still loads
  `JFREventContextIntegration` the same way for the JFR branch, untouched by this PR.
- `dd-java-agent/agent-bootstrap/build.gradle` has no `project(':dd-java-agent:agent-profiling...')`
  dependency - confirmed by grep, empty result. `agent-bootstrap` runs in premain under strict
  bootstrap constraints (no `java.nio.file`, no JMX - see
  `docs/bootstrap_design_guidelines.md`) and intentionally has no compile-time dependency on the
  much heavier `agent-profiling` module (ddprof native bindings, JFR controllers).
- `DatadogProfilingIntegration.java` and `ProcessContext.java` live under
  `dd-java-agent/agent-profiling/...`. They are always present in the single shaded agent jar
  regardless of `DD_PROFILING_ENABLED` - "profiling enabled" is a runtime flag deciding whether to
  *instantiate* these classes, not whether the jar contains them. So `isDatadogProfilerSafeAndConfigured()`
  correctly has no dependency on profiling being active: it only checks JVM/platform safety
  (`isDatadogProfilerEnablementOverridden()`, `isDatadogProfilerSafeInCurrentEnvironment()`) plus the
  `PROFILING_DATADOG_PROFILER_ENABLED` sub-flag, whose own default (`isDatadogProfilerSafeInCurrentEnvironment()`)
  is likewise independent of `DD_PROFILING_ENABLED` - verified across all its usages
  (`Config.java`, `OpenJdkController.java`; no other read site exists).

**Decision:** keep the reflective load. Moving `DatadogProfilingIntegration`/`ProcessContext` to a
shared module to avoid reflection would mean redesigning the intentional bootstrap/profiling module
boundary - much higher risk and blast radius than reusing an established, working pattern this PR
only extends (new OR condition + deferred construction), not invents.

**Follow-up naming note:** `isDatadogProfilerEnabled()` (the pre-existing getter, unchanged by this
PR) is a false friend - it sounds like "the profiler is currently recording" but is actually
`isProfilingEnabled() && isDatadogProfilerSafeAndConfigured()`. Documented with a clarifying Javadoc
on the getter in this PR. A full rename (`isDatadogProfilerEnabled()` →
`isDatadogProfilerActive()`, raw field → `ddprofEngineAllowed`) was considered but rejected for this
PR: the getter has wide call-site exposure (`StatusLogger`, `ProfilerFlareReporter`,
`CompositeController`, `ProfilerSettingsSupport`, JFR controllers) and renaming it would mix an
unrelated wide rename into a PR already touching bootstrap/premain.
