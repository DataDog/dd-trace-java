# Anti-Patterns

Common mistakes when writing dd-trace-java instrumentations. Each item has the **symptom**, the **cause**, and the **fix**. For positive examples (the "right" pattern in production code), see the cited reference integrations.

For the underlying rules, see [`bytebuddy-patterns.md`](bytebuddy-patterns.md) (R1-R14) — most anti-patterns here are violations of those rules.

## Advice Class Mistakes

### ❌ Lambdas in Advice methods

**Symptom**: muzzle passes but runtime fails with `NoClassDefFoundError`.
**Cause**: lambdas create synthetic classes via `invokedynamic` that aren't in `helperClassNames()` and break when Advice is inlined.
**Fix**: use plain `for` loops or named anonymous inner classes (declared in `helperClassNames()`).
**Rule**: R1.

### ❌ Logger fields in Advice classes

**Symptom**: NPE or class-loading failure when the Advice runs in a real app.
**Cause**: `private static final Logger log = ...` on the Advice class — the Advice gets inlined into target methods, and the field doesn't survive.
**Fix**: declare the logger on a helper class or decorator, call `Helper.logEntry(...)` from Advice.
**Reference**: `dd-java-agent/instrumentation/jdbc/src/main/java/.../JDBCDecorator.java` (logger lives on the decorator).
**Rule**: R2.

### ❌ Missing `suppress = Throwable.class` on Advice

**Symptom**: an exception inside Advice code crashes the instrumented application.
**Cause**: `@Advice.OnMethodEnter` (no suppress) lets Advice exceptions propagate into the target method.
**Fix**: `@Advice.OnMethodEnter(suppress = Throwable.class)` on enter, `@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)` on exit. **Exception**: do NOT use `suppress` on constructor instrumentation — constructors must surface initialization failures.

### ❌ No CallDepth guard on recursive/reentrant calls

**Symptom**: duplicate spans for one logical operation; trace tree shows nested spans of the same name.
**Cause**: the instrumented method calls itself (or a sibling instrumented method) and each entry creates a span.
**Fix**: `CallDepthThreadLocalMap.incrementCallDepth(MyClass.class)` on enter, `reset` on exit; bail out when depth > 0.
**Reference**: `dd-java-agent/instrumentation/jdbc/src/main/java/.../StatementInstrumentation.java`.
**Rule**: R5.
**Caveat**: not safe across async boundaries (different thread → different ThreadLocal).

## Span Lifecycle Mistakes

### ❌ Not finishing spans

**Symptom**: memory leak; spans never reach the agent; trace UI shows started-but-unfinished operations.
**Cause**: span created in enter but `span.finish()` missing from exit.
**Fix**: every enter that calls `startSpan()` must have a corresponding `span.finish()` and `scope.close()` in exit.
**Rule**: R6.

### ❌ Not tagging errors

**Symptom**: APM shows success even though the operation threw; error rate metrics are wrong.
**Cause**: exit method doesn't check `@Advice.Thrown Throwable throwable` or doesn't call `DECORATE.onError(span, throwable)` when non-null.
**Fix**: `if (throwable != null) DECORATE.onError(span, throwable);` BEFORE `DECORATE.beforeFinish(span);` and `span.finish();`.
**Rule**: R8.

### ❌ Not activating spans

**Symptom**: distributed tracing context doesn't propagate; downstream spans have no parent.
**Cause**: span created via `startSpan()` but never activated.
**Fix**: `AgentScope scope = activateSpan(span); return scope;` from `@Advice.OnMethodEnter`. Always pair with `scope.close()` in exit.
**Rule**: R7.

### ❌ Wrong lifecycle order

**Symptom**: errors appear on later spans; tags missing on errored spans; double-finish errors.
**Cause**: lifecycle calls reordered. Strict order is **enter** `startSpan` → `afterStart` → `activateSpan`; **exit** `onError` (if thrown) → `beforeFinish` → `finish` → `scope.close`.
**Fix**: copy the exact sequence from a reference integration's Advice (`okhttp-3`, `jdbc`, `kafka-clients-0.11`).
**Rule**: R8.

## Module Configuration Mistakes

### ❌ Missing helper declarations

**Symptom**: tests pass (testing JAR provides the helper), but production fails with `NoClassDefFoundError`. Or muzzle fails with "missing type".
**Cause**: a class referenced from Advice (or transitively from a helper) isn't in `helperClassNames()`.
**Fix**: every type the Advice or any of its helpers can reach must be listed — including inner classes (`Foo$Bar`), anonymous (`Foo$1`), and synthetic enum classes.
**Verification**: `./gradlew :dd-java-agent:instrumentation:<module>:muzzle` catches missing helpers locally.
**Rule**: R4, R11.

### ❌ Wrong muzzle ranges

**Symptom**: at runtime, the integration loads against an incompatible library version and fails with `NoSuchMethodError` / `NoSuchFieldError`.
**Cause**: muzzle's `pass { versions = "[0,)" }` allows any version, but the Advice references APIs that don't exist below some minimum.
**Fix**: tighten to a real minimum (e.g., `[3.0,)`), and add `assertInverse = true` so muzzle FAILS for versions below the minimum (catching future regressions). For incompatible older versions, add explicit `fail { versions = "[,3.0)" }`.
**Reference**: `dd-java-agent/instrumentation/okhttp-3/build.gradle` for typical pattern.
**Rule**: R9.

### ❌ Multiple `InstrumenterModule`s in one submodule

**Symptom**: confusing helper resolution, hard-to-debug muzzle failures, unclear which module activates.
**Cause**: trying to support multiple library major versions from a single submodule.
**Fix**: split into versioned submodules (`okhttp-2/`, `okhttp-3/`). Each has its own `InstrumenterModule`, registered separately in `settings.gradle.kts`.
**Rule**: R3.

## Test Mistakes

### ❌ Not waiting for traces

**Symptom**: flaky tests; assertions sometimes pass and sometimes fail.
**Cause**: tests check trace state immediately after the action without `TEST_WRITER.waitForTraces(N)`. The agent collects spans asynchronously.
**Fix**: `TEST_WRITER.waitForTraces(expectedCount)` before asserting. For Spock specs, `assertTraces(N) { ... }` does this internally.
**Reference**: any reference test under `dd-java-agent/instrumentation/*/src/test/groovy/`.

### ❌ Skipping failing tests

**Symptom**: silent regressions; CI green but feature broken.
**Cause**: `@Ignore`, `@IgnoreIf`, `pytest.skip()` (or Spock equivalent) on a test that started failing.
**Fix**: never skip. If a test legitimately can't run in CI (e.g., flaky external dependency), use Testcontainers, mock the dependency, or move the test to a separate `forkedTest` task that runs conditionally — but assert real behavior. If a feature is genuinely broken, fix it or remove the integration.

### ❌ Not testing the error path

**Symptom**: production traces show errors not tagged on spans; `error` rate metric inaccurate.
**Cause**: tests only exercise success path; error tagging (R8) regresses silently.
**Fix**: every integration test class must include at least one test that triggers the library's error path (4xx/5xx HTTP, SQLException, message-broker disconnect) and asserts `errorTags()` is set on the span.

### ❌ Mocking the library instead of using a real instance

**Symptom**: tests pass but the integration breaks against the real library.
**Cause**: mocked `Connection`/`Client`/etc. doesn't exercise the bytecode paths ByteBuddy instrumented.
**Fix**: use Testcontainers or an embedded server. The instrumentation only takes effect when the real bytecode runs.
**Reference**: Kafka tests use embedded broker; JDBC tests use H2/Derby; HTTP-server tests use Netty/Undertow embedded.

## Tagging Mistakes

### ❌ Missing required tags

**Symptom**: APM features (Service Catalog, Service Map, peer.service computation) don't work for this integration.
**Cause**: tags listed in R13 weren't set, OR they were set with wrong names (e.g., `db.host` instead of `peer.hostname`).
**Fix**: tag names live in `dd-java-agent/agent-bootstrap/src/main/java/.../api/Tags.java` — use the constants. Base decorators set most of these automatically; ensure your decorator extends the right base class for your category.
**Rule**: R13.

### ❌ Wrong span kind

**Symptom**: integration appears in the wrong place on the service map (or doesn't appear).
**Cause**: `spanKind()` returns the wrong value for the integration type.
**Fix**: see R12 for the correct mapping. Set in your decorator's `spanKind()` override.
**Rule**: R12.

### ❌ Setting `peer.service` directly

**Symptom**: user-config peer.service overrides (`dd.trace.peer.service.mapping`) don't take effect for this integration.
**Cause**: integration sets `peer.service` directly on the span, bypassing the `PeerServiceCalculator`.
**Fix**: set the input tags (`peer.hostname`, `db.instance`, `messaging.destination`, etc.) and let the calculator derive `peer.service`.
**Reference**: `dd-trace-core/src/main/java/datadog/trace/core/tagprocessor/PeerServiceCalculator.java`.

## Build and CI Mistakes

### ❌ Leaving debug code in production

**Symptom**: integration runs slower than necessary, or generates spurious log output.
**Cause**: `inline = false` on Advice annotations (debug-only — Advice always inlines in production), `System.out.println` calls, commented-out test code.
**Fix**: search for `inline = false`, `System.out`, `printStackTrace`, `// TODO`, `// XXX` before submitting.

### ❌ Forgetting Spotless

**Symptom**: CI fails on formatting before tests even run.
**Cause**: code committed without running spotless.
**Fix**: `./gradlew spotlessApply` before commit; CI runs `spotlessCheck` and fails on formatting violations.
**Rule**: R14.

### ❌ Skipping muzzle / latestDepTest

**Symptom**: integration broken against latest library version, or against versions outside tested range.
**Cause**: only ran the local `:test` task, missed `:muzzle` and `:latestDepTest`.
**Fix**: before submitting, run all four:
```bash
./gradlew :dd-java-agent:instrumentation:<module>:muzzle
./gradlew :dd-java-agent:instrumentation:<module>:test
./gradlew :dd-java-agent:instrumentation:<module>:latestDepTest
./gradlew spotlessCheck
```
