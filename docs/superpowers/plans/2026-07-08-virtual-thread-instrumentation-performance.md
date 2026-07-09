# Virtual Thread Instrumentation Performance Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop swapping the whole scope stack on every virtual-thread park/unpark; seed the context once and, only when carrier-bound (ddprof) profiling is active, do an allocation-free profiler rebind/unbind per mount/unmount.

**Architecture:** The trace scope stack lives in a native `ThreadLocal<ScopeStack>` that is virtual-thread-aware (follows the VT across park/unpark and carrier migration), so it needs seeding exactly once on first mount. The ddprof profiler context lives in a carrier-OS-thread-keyed native slot that is *not* VT-aware, so it needs a cheap re-apply on mount and clear on unmount — but only for integrations that report themselves carrier-bound. A new capability flag on `ProfilingContextIntegration` plus two new `TracerAPI`/`ContinuableScopeManager` methods keep ddprof knowledge in dd-trace-core; `VirtualThreadState` calls them.

**Tech Stack:** Java, ByteBuddy advice (java-lang-21.0 instrumentation), JUnit 5, dd-trace-core scope manager.

## Global Constraints

- Instrumentation advice/helper code must compile to Java 8 bytecode; `VirtualThreadState` and `VirtualThreadInstrumentation` must not use APIs newer than the module allows. (Existing constraint — do not introduce new JDK 21 API usage in these files.)
- `VirtualThreadState` is a bootstrap helper (`agent-bootstrap`), reached from advice; it may use `datadog.context.Context`, `AgentTracer`, and `AgentScope.Continuation` (all bootstrap-visible). It must NOT reference ddprof/profiling implementation classes directly.
- New unit tests: JUnit 5 + Java. Instrumentation tests stay in the existing JUnit 5 Java suite under `testdog.trace.instrumentation.java.lang.jdk21`.
- Run java-lang-21.0 tests on JDK 21: `-PtestJvm=21`.
- Format with `./gradlew spotlessApply` before each commit.
- Do NOT auto-commit unless a step says to; the repo owner reviews diffs. (This plan includes commit steps; the executor should still surface diffs.)

## File Structure

- `internal-api/src/main/java/datadog/trace/bootstrap/instrumentation/api/ProfilingContextIntegration.java` — add `default boolean isCarrierThreadBound()`.
- `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilingIntegration.java` — override `isCarrierThreadBound()` → `true`.
- `dd-trace-core/src/main/java/datadog/trace/core/scopemanager/ContinuableScope.java` — add `deactivateProfiling()`.
- `dd-trace-core/src/main/java/datadog/trace/core/scopemanager/ContinuableScopeManager.java` — add `profilerCarrierBound` field + `rebindProfilingContextToCarrier()` / `unbindProfilingContextFromCarrier()`.
- `internal-api/src/main/java/datadog/trace/bootstrap/instrumentation/api/AgentTracer.java` — add two `default` no-op methods to `TracerAPI`.
- `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java` — override the two methods, delegating to `scopeManager`.
- `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/java/lang/VirtualThreadState.java` — rewrite to seed-once + rebind/unbind.
- `dd-java-agent/instrumentation/java/java-lang/java-lang-21.0/.../VirtualThreadInstrumentation.java` — javadoc/comment update only (advice bodies unchanged).
- Tests: `dd-trace-core/src/test/java/datadog/trace/core/scopemanager/CarrierProfilerRebindTest.java` (new), and additions to `.../java-lang-21.0/src/test/java/testdog/trace/instrumentation/java/lang/jdk21/VirtualThreadLifeCycleTest.java`.

---

### Task 1: Carrier-bound capability flag on `ProfilingContextIntegration`

**Files:**
- Modify: `internal-api/src/main/java/datadog/trace/bootstrap/instrumentation/api/ProfilingContextIntegration.java`
- Modify: `dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilingIntegration.java`
- Test: `internal-api/src/test/java/datadog/trace/bootstrap/instrumentation/api/ProfilingContextIntegrationTest.java` (new)

**Interfaces:**
- Produces: `boolean ProfilingContextIntegration.isCarrierThreadBound()` — default `false`; `true` for ddprof. Used by Task 2 to gate the profiler rebind/unbind.

- [ ] **Step 1: Write the failing test**

Create `internal-api/src/test/java/datadog/trace/bootstrap/instrumentation/api/ProfilingContextIntegrationTest.java`:

```java
package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class ProfilingContextIntegrationTest {
  @Test
  void defaultIntegrationIsNotCarrierThreadBound() {
    ProfilingContextIntegration integration = () -> "test-only";
    assertFalse(integration.isCarrierThreadBound());
  }

  @Test
  void noOpIntegrationIsNotCarrierThreadBound() {
    assertFalse(ProfilingContextIntegration.NoOp.INSTANCE.isCarrierThreadBound());
  }
}
```

Note: `ProfilingContextIntegration` has a single abstract method `name()`, so `() -> "test-only"` is a valid lambda instance.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :internal-api:test --tests 'datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegrationTest'`
Expected: FAIL to COMPILE — `cannot find symbol: method isCarrierThreadBound()`.

- [ ] **Step 3: Add the default method**

In `ProfilingContextIntegration.java`, add immediately after the `onDetach()` default method (around line 19):

```java
  /**
   * Whether this integration stores the active span context in a carrier / OS-thread-keyed slot
   * (e.g. ddprof's native {@code setContext}) rather than a virtual-thread-aware Java {@code
   * ThreadLocal}. When {@code true}, the context must be re-applied to the current carrier on every
   * virtual-thread mount and cleared on unmount.
   */
  default boolean isCarrierThreadBound() {
    return false;
  }
```

- [ ] **Step 4: Override in the ddprof integration**

In `DatadogProfilingIntegration.java`, add this method (next to `name()`, around lines 76-79):

```java
  @Override
  public boolean isCarrierThreadBound() {
    return true;
  }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :internal-api:test --tests 'datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegrationTest'`
Expected: PASS (2 tests).

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add internal-api/src/main/java/datadog/trace/bootstrap/instrumentation/api/ProfilingContextIntegration.java \
        internal-api/src/test/java/datadog/trace/bootstrap/instrumentation/api/ProfilingContextIntegrationTest.java \
        dd-java-agent/agent-profiling/profiling-ddprof/src/main/java/com/datadog/profiling/ddprof/DatadogProfilingIntegration.java
git commit -m "Add ProfilingContextIntegration.isCarrierThreadBound capability flag"
```

---

### Task 2: Profiler rebind/unbind on the scope manager

**Files:**
- Modify: `dd-trace-core/src/main/java/datadog/trace/core/scopemanager/ContinuableScope.java`
- Modify: `dd-trace-core/src/main/java/datadog/trace/core/scopemanager/ContinuableScopeManager.java`
- Test: `dd-trace-core/src/test/java/datadog/trace/core/scopemanager/CarrierProfilerRebindTest.java` (new)

**Interfaces:**
- Consumes: `ProfilingContextIntegration.isCarrierThreadBound()` (Task 1); existing package-private `ContinuableScopeManager.scopeStack()`, `ScopeStack.active()`, public `ContinuableScope.beforeActivated()`.
- Produces:
  - `void ContinuableScope.deactivateProfiling()` — clears the scope's profiler state without closing the scope.
  - `void ContinuableScopeManager.rebindProfilingContextToCarrier()` — re-applies the active scope's profiler context to the current carrier; no-op unless carrier-bound profiling is on.
  - `void ContinuableScopeManager.unbindProfilingContextFromCarrier()` — clears the current carrier's profiler slot; same gating.

- [ ] **Step 1: Write the failing test**

Create `dd-trace-core/src/test/java/datadog/trace/core/scopemanager/CarrierProfilerRebindTest.java`:

```java
package datadog.trace.core.scopemanager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.context.ContextScope;
import datadog.trace.api.Stateful;
import datadog.trace.api.profiling.ProfilingContextAttribute;
import datadog.trace.api.profiling.ProfilingScope;
import datadog.trace.api.profiling.Timer.TimerType;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.EndpointTracker;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CarrierProfilerRebindTest {

  static final CoreTracer TRACER = CoreTracer.builder().build();

  /** Records activate/close counts so the test can observe carrier rebind/unbind. */
  static final class CountingProfiling implements ProfilingContextIntegration {
    final AtomicInteger activations = new AtomicInteger();
    final AtomicInteger closes = new AtomicInteger();
    private final boolean carrierBound;

    CountingProfiling(boolean carrierBound) {
      this.carrierBound = carrierBound;
    }

    @Override
    public boolean isCarrierThreadBound() {
      return carrierBound;
    }

    @Override
    public Stateful newScopeState(ProfilerContext profilerContext) {
      return new Stateful() {
        @Override
        public void activate(Object context) {
          activations.incrementAndGet();
        }

        @Override
        public void close() {
          closes.incrementAndGet();
        }
      };
    }

    @Override
    public String name() {
      return "counting";
    }

    @Override
    public ProfilingContextAttribute createContextAttribute(String attribute) {
      return ProfilingContextAttribute.NoOp.INSTANCE;
    }

    @Override
    public ProfilingScope newScope() {
      return ProfilingScope.NO_OP;
    }

    @Override
    public void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {}

    @Override
    public EndpointTracker onRootSpanStarted(AgentSpan rootSpan) {
      return EndpointTracker.NO_OP;
    }

    @Override
    public Timing start(TimerType type) {
      return Timing.NoOp.INSTANCE;
    }
  }

  @Test
  void rebindAndUnbindDriveProfilerWhenCarrierBound() {
    CountingProfiling profiling = new CountingProfiling(true);
    ContinuableScopeManager manager =
        new ContinuableScopeManager(0, false, profiling, HealthMetrics.NO_OP);
    AgentSpan span = TRACER.startSpan("test", "op");
    try (ContextScope scope = manager.attach(span)) {
      // attach already activated the profiler once; measure only rebind/unbind from here.
      profiling.activations.set(0);
      profiling.closes.set(0);

      manager.rebindProfilingContextToCarrier();
      manager.unbindProfilingContextFromCarrier();

      assertEquals(1, profiling.activations.get(), "rebind should re-apply profiler context");
      assertEquals(1, profiling.closes.get(), "unbind should clear profiler context");
    } finally {
      span.finish();
    }
  }

  @Test
  void rebindAndUnbindAreNoOpWhenNotCarrierBound() {
    CountingProfiling profiling = new CountingProfiling(false);
    ContinuableScopeManager manager =
        new ContinuableScopeManager(0, false, profiling, HealthMetrics.NO_OP);
    AgentSpan span = TRACER.startSpan("test", "op");
    try (ContextScope scope = manager.attach(span)) {
      profiling.activations.set(0);
      profiling.closes.set(0);

      manager.rebindProfilingContextToCarrier();
      manager.unbindProfilingContextFromCarrier();

      assertEquals(0, profiling.activations.get(), "no rebind when not carrier bound");
      assertEquals(0, profiling.closes.get(), "no unbind when not carrier bound");
    } finally {
      span.finish();
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :dd-trace-core:test --tests 'datadog.trace.core.scopemanager.CarrierProfilerRebindTest'`
Expected: FAIL to COMPILE — `cannot find symbol: method rebindProfilingContextToCarrier()` / `unbindProfilingContextFromCarrier()`.

- [ ] **Step 3: Add `deactivateProfiling()` to `ContinuableScope`**

In `ContinuableScope.java`, add after `beforeActivated()` (after line 178):

```java
  /** Clears the profiler context for this scope's state without closing the scope itself. */
  public final void deactivateProfiling() {
    if (scopeState == Stateful.DEFAULT) {
      return;
    }
    try {
      scopeState.close();
    } catch (Throwable e) {
      ContinuableScopeManager.ratelimitedLog.warn(
          "ScopeState {} threw exception in deactivateProfiling()", scopeState.getClass(), e);
    }
  }
```

- [ ] **Step 4: Add the field and methods to `ContinuableScopeManager`**

In `ContinuableScopeManager.java`, add a field next to `profilingEnabled` (after line 64):

```java
  private final boolean profilerCarrierBound;
```

In the 4-arg constructor, immediately after the `this.profilingEnabled = ...` assignment (after line 97), add:

```java
    this.profilerCarrierBound =
        this.profilingEnabled && profilingContextIntegration.isCarrierThreadBound();
```

Add these two public methods near `scopeStack()` (after line 371):

```java
  /**
   * Re-applies the active scope's profiler context to the current carrier thread. No-op unless a
   * carrier-thread-bound profiling integration (e.g. ddprof) is active. Called on virtual-thread
   * mount, where the carrier's native context slot must be refreshed after (possibly) migrating
   * carriers.
   */
  public void rebindProfilingContextToCarrier() {
    if (!profilerCarrierBound) {
      return;
    }
    final ContinuableScope active = scopeStack().active();
    if (active != null) {
      active.beforeActivated();
    }
  }

  /**
   * Clears the current carrier thread's profiler context slot. No-op unless a carrier-thread-bound
   * profiling integration is active. Called on virtual-thread unmount so the carrier is not left
   * attributed to the virtual thread's span.
   */
  public void unbindProfilingContextFromCarrier() {
    if (!profilerCarrierBound) {
      return;
    }
    final ContinuableScope active = scopeStack().active();
    if (active != null) {
      active.deactivateProfiling();
    }
  }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :dd-trace-core:test --tests 'datadog.trace.core.scopemanager.CarrierProfilerRebindTest'`
Expected: PASS (2 tests).

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add dd-trace-core/src/main/java/datadog/trace/core/scopemanager/ContinuableScope.java \
        dd-trace-core/src/main/java/datadog/trace/core/scopemanager/ContinuableScopeManager.java \
        dd-trace-core/src/test/java/datadog/trace/core/scopemanager/CarrierProfilerRebindTest.java
git commit -m "Add carrier profiler rebind/unbind to ContinuableScopeManager"
```

---

### Task 3: Expose rebind/unbind on `TracerAPI` and `CoreTracer`

**Files:**
- Modify: `internal-api/src/main/java/datadog/trace/bootstrap/instrumentation/api/AgentTracer.java`
- Modify: `dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java`
- Test: `dd-trace-core/src/test/java/datadog/trace/core/CoreTracerCarrierRebindTest.java` (new)

**Interfaces:**
- Consumes: `ContinuableScopeManager.rebindProfilingContextToCarrier()` / `unbindProfilingContextFromCarrier()` (Task 2).
- Produces: `void TracerAPI.rebindProfilingContextToCarrier()` and `void TracerAPI.unbindProfilingContextFromCarrier()` — `default` no-ops on the interface, delegating overrides on `CoreTracer`. Used by Task 4 via `AgentTracer.get()`.

- [ ] **Step 1: Write the failing test**

Create `dd-trace-core/src/test/java/datadog/trace/core/CoreTracerCarrierRebindTest.java`:

```java
package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import org.junit.jupiter.api.Test;

class CoreTracerCarrierRebindTest {

  @Test
  void tracerRebindUnbindAreSafeNoOpsWithoutProfiling() {
    CoreTracer tracer = CoreTracer.builder().build();
    AgentSpan span = tracer.startSpan("test", "op");
    try (AgentScopeCloser ignored = new AgentScopeCloser(tracer.activateSpan(span))) {
      // Profiling is disabled by default, so both calls must be harmless no-ops.
      assertDoesNotThrow(tracer::rebindProfilingContextToCarrier);
      assertDoesNotThrow(tracer::unbindProfilingContextFromCarrier);
    } finally {
      span.finish();
    }
  }

  @Test
  void noopTracerRebindUnbindDoNotThrow() {
    AgentTracer.TracerAPI noop = AgentTracer.NoopTracerAPI.INSTANCE;
    assertDoesNotThrow(noop::rebindProfilingContextToCarrier);
    assertDoesNotThrow(noop::unbindProfilingContextFromCarrier);
  }

  /** Minimal try-with-resources helper around AgentScope. */
  static final class AgentScopeCloser implements AutoCloseable {
    private final datadog.trace.bootstrap.instrumentation.api.AgentScope scope;

    AgentScopeCloser(datadog.trace.bootstrap.instrumentation.api.AgentScope scope) {
      this.scope = scope;
    }

    @Override
    public void close() {
      scope.close();
    }
  }
}
```

Note: verify `AgentTracer.NoopTracerAPI.INSTANCE` is accessible from this package; the exploration found `NoopTracerAPI` is a static nested class in `AgentTracer`. If `INSTANCE` is not visible, replace the second test body with `assertDoesNotThrow(() -> ((AgentTracer.TracerAPI) AgentTracer.get()).rebindProfilingContextToCarrier())` after confirming the default provider — but prefer the explicit NoopTracerAPI reference if visible.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :dd-trace-core:test --tests 'datadog.trace.core.CoreTracerCarrierRebindTest'`
Expected: FAIL to COMPILE — `cannot find symbol: method rebindProfilingContextToCarrier()`.

- [ ] **Step 3: Add default no-op methods to `TracerAPI`**

In `AgentTracer.java`, inside the `TracerAPI` interface, add after `getProfilingContext()` (after line 391):

```java
    /**
     * Re-applies the active scope's profiler context to the current carrier thread. No-op unless a
     * carrier-thread-bound profiling integration is active. Used by virtual-thread instrumentation
     * on mount.
     */
    default void rebindProfilingContextToCarrier() {}

    /**
     * Clears the current carrier thread's profiler context slot. No-op unless a carrier-thread-bound
     * profiling integration is active. Used by virtual-thread instrumentation on unmount.
     */
    default void unbindProfilingContextFromCarrier() {}
```

Because these are `default` methods, `NoopTracerAPI` needs no change.

- [ ] **Step 4: Override in `CoreTracer` to delegate**

In `CoreTracer.java`, add near the other scope delegations (the class has a `private final ContinuableScopeManager scopeManager;` field). Place after an existing scope-delegating method such as `addScopeListener`:

```java
  @Override
  public void rebindProfilingContextToCarrier() {
    scopeManager.rebindProfilingContextToCarrier();
  }

  @Override
  public void unbindProfilingContextFromCarrier() {
    scopeManager.unbindProfilingContextFromCarrier();
  }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :dd-trace-core:test --tests 'datadog.trace.core.CoreTracerCarrierRebindTest'`
Expected: PASS (2 tests).

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add internal-api/src/main/java/datadog/trace/bootstrap/instrumentation/api/AgentTracer.java \
        dd-trace-core/src/main/java/datadog/trace/core/CoreTracer.java \
        dd-trace-core/src/test/java/datadog/trace/core/CoreTracerCarrierRebindTest.java
git commit -m "Expose carrier profiler rebind/unbind on TracerAPI and CoreTracer"
```

---

### Task 4: Rewrite `VirtualThreadState` to seed-once + rebind/unbind

**Files:**
- Modify: `dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/java/lang/VirtualThreadState.java`
- Modify: `dd-java-agent/instrumentation/java/java-lang/java-lang-21.0/src/main/java/datadog/trace/instrumentation/java/lang/jdk21/VirtualThreadInstrumentation.java` (javadoc only)
- Test: existing `.../java-lang-21.0/src/test/java/testdog/trace/instrumentation/java/lang/jdk21/VirtualThreadLifeCycleTest.java` and `VirtualThreadApiInstrumentationTest.java` (regression guard — no new file here; new migration test added in Task 5)

**Interfaces:**
- Consumes: `AgentTracer.get().rebindProfilingContextToCarrier()` / `unbindProfilingContextFromCarrier()` (Task 3); `Context.swap()`; existing constructor signature `VirtualThreadState(Context, AgentScope.Continuation)` — unchanged so `VirtualThreadInstrumentation$Construct` needs no change.
- Produces: `onMount()`, `onUnmount()`, `onTerminate()` (same names/signatures the advice already calls).

- [ ] **Step 1: Confirm the regression baseline is green (pre-change)**

Run: `./gradlew :dd-java-agent:instrumentation:java:java-lang:java-lang-21.0:test -PtestJvm=21`
Expected: PASS. This is the behavioral contract the rewrite must preserve. (If it fails before any change, stop and investigate the environment.)

- [ ] **Step 2: Rewrite `VirtualThreadState.java`**

Replace the entire file with:

```java
package datadog.trace.bootstrap.instrumentation.java.lang;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentScope.Continuation;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

/**
 * Holds the seed context and scope continuation for a virtual thread.
 *
 * <p>Used by java-lang-21.0 {@code VirtualThreadInstrumentation}. The virtual thread's scope stack
 * lives in a virtual-thread-aware {@code ThreadLocal}, so it only needs seeding once on the first
 * mount; it then follows the virtual thread across park/unpark and carrier migration on its own. On
 * subsequent mounts/unmounts the only per-cycle work is a lightweight profiler rebind/unbind, and
 * only when a carrier-thread-bound profiling integration (ddprof) is active — otherwise those calls
 * are no-ops.
 */
public final class VirtualThreadState {
  /** The parent context captured at construction; installed once on first mount, then released. */
  private Context seedContext;

  /** Prevents the enclosing context scope from completing before the virtual thread finishes. */
  private final Continuation continuation;

  /** Whether the seed context has been installed into the virtual thread's scope stack. */
  private boolean seeded;

  public VirtualThreadState(Context seedContext, Continuation continuation) {
    this.seedContext = seedContext;
    this.continuation = continuation;
  }

  /**
   * Called on mount (running as the virtual thread). On the first mount, installs the seed context
   * into the virtual thread's scope stack (this also applies the profiler context to the first
   * carrier). On later mounts, re-applies the active scope's profiler context to the current
   * carrier.
   */
  public void onMount() {
    if (!seeded) {
      seedContext.swap();
      seeded = true;
      seedContext = null;
    } else {
      AgentTracer.get().rebindProfilingContextToCarrier();
    }
  }

  /** Called on unmount (running as the virtual thread): clears the carrier's profiler context. */
  public void onUnmount() {
    AgentTracer.get().unbindProfilingContextFromCarrier();
  }

  /** Called on termination: releases the trace continuation. */
  public void onTerminate() {
    if (this.continuation != null) {
      this.continuation.cancel();
    }
  }
}
```

- [ ] **Step 3: Update the instrumentation javadoc (no behavioral change)**

In `VirtualThreadInstrumentation.java`, replace the class-level lifecycle javadoc (lines 33-60) description of mount/unmount so it reflects seed-once semantics. Change the `<li>{@code mount()}` and `<li>{@code unmount()}` bullets to:

```java
 *   <li>{@code mount()}: on the first mount, seeds the virtual thread's scope stack with the
 *       captured context; on later mounts, re-applies the profiler context to the current carrier
 *       (no-op unless carrier-bound profiling is active).
 *   <li>{@code unmount()}: clears the carrier's profiler context (no-op unless carrier-bound
 *       profiling is active). The scope stack is NOT swapped out — it lives in the virtual thread's
 *       own thread-local and follows it across park/unpark and carrier migration.
```

Leave the advice classes (`Construct`, `Mount`, `Unmount`, `AfterDone`), `contextStore()`, `excludedClasses()`, and `preloadClassNames()` unchanged.

- [ ] **Step 4: Run the regression suite to verify behavior is preserved**

Run: `./gradlew :dd-java-agent:instrumentation:java:java-lang:java-lang-21.0:test -PtestJvm=21`
Expected: PASS — all existing `VirtualThreadLifeCycleTest` and `VirtualThreadApiInstrumentationTest` cases green (parent inheritance, context restored after remount, child-span ordering across unmount/remount, concurrent VTs, no-context VT).

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/java/lang/VirtualThreadState.java \
        dd-java-agent/instrumentation/java/java-lang/java-lang-21.0/src/main/java/datadog/trace/instrumentation/java/lang/jdk21/VirtualThreadInstrumentation.java
git commit -m "Seed virtual-thread context once instead of swapping per mount/unmount"
```

---

### Task 5: Add a carrier-migration regression test

**Files:**
- Modify: `dd-java-agent/instrumentation/java/java-lang/java-lang-21.0/src/test/java/testdog/trace/instrumentation/java/lang/jdk21/VirtualThreadLifeCycleTest.java`

**Interfaces:**
- Consumes: the rewritten `VirtualThreadState` behavior (Task 4). Uses the existing `AbstractInstrumentationTest`, `@Trace`, `GlobalTracer`, `span()`/`trace()` assertion DSL already imported in the file.

- [ ] **Step 1: Write the failing-then-passing test**

Add this method to `VirtualThreadLifeCycleTest` (before the private helpers at line 215). It forces a small carrier pool so virtual threads are highly likely to resume on different carriers, and asserts context is preserved and a child span is parented correctly across migration:

```java
  @DisplayName("test context preserved across carrier migration")
  @Test
  void testContextPreservedAcrossCarrierMigration() {
    // Constrain the carrier pool so parked VTs resume on different carriers.
    String previousParallelism =
        System.getProperty("jdk.virtualThreadScheduler.parallelism");
    System.setProperty("jdk.virtualThreadScheduler.parallelism", "2");
    try {
      int threadCount = 32;
      String[] parentSpanId = new String[1];
      String[] childParentSpanIds = new String[threadCount];

      new Runnable() {
        @Override
        @Trace(operationName = "parent")
        public void run() {
          parentSpanId[0] = GlobalTracer.get().getSpanId();
          List<Thread> threads = new ArrayList<>();
          for (int i = 0; i < threadCount; i++) {
            int index = i;
            threads.add(
                Thread.startVirtualThread(
                    () -> {
                      // Multiple park/unpark cycles to provoke carrier migration.
                      tryUnmount();
                      childParentSpanIds[index] = GlobalTracer.get().getSpanId();
                    }));
          }
          for (Thread thread : threads) {
            try {
              thread.join(TIMEOUT);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
          }
        }
      }.run();

      for (int i = 0; i < threadCount; i++) {
        assertEquals(
            parentSpanId[0],
            childParentSpanIds[i],
            "context must survive park/unpark and carrier migration for VT #" + i);
      }
      assertTraces(trace(span().root().operationName("parent")));
    } finally {
      if (previousParallelism == null) {
        System.clearProperty("jdk.virtualThreadScheduler.parallelism");
      } else {
        System.setProperty("jdk.virtualThreadScheduler.parallelism", previousParallelism);
      }
    }
  }
```

Note: `jdk.virtualThreadScheduler.parallelism` is read when the default scheduler initializes. If the suite has already started virtual threads, this property may have no effect on the running scheduler; the test still validates propagation across many park/unpark cycles regardless. Keep the property set for best-effort migration.

- [ ] **Step 2: Run the test**

Run: `./gradlew :dd-java-agent:instrumentation:java:java-lang:java-lang-21.0:test -PtestJvm=21 --tests '*VirtualThreadLifeCycleTest'`
Expected: PASS, including the new `testContextPreservedAcrossCarrierMigration`.

- [ ] **Step 3: Format and commit**

```bash
./gradlew spotlessApply
git add dd-java-agent/instrumentation/java/java-lang/java-lang-21.0/src/test/java/testdog/trace/instrumentation/java/lang/jdk21/VirtualThreadLifeCycleTest.java
git commit -m "Add carrier-migration regression test for virtual-thread context"
```

---

### Task 6: Verify profiler attribution end-to-end (manual/smoke)

**Files:**
- No source change. Verification only.

**Interfaces:**
- Consumes: full agent build with ddprof profiling enabled.

- [ ] **Step 1: Build the agent**

Run: `./gradlew :dd-java-agent:shadowJar -PtestJvm=21`
Expected: BUILD SUCCESSFUL; jar in `dd-java-agent/build/libs/`.

- [ ] **Step 2: Run existing virtual-thread / profiling smoke tests**

Locate and run the virtual-thread smoke tests and any profiling smoke tests, on JDK 21+:

Run: `./gradlew :dd-smoke-tests:... :test -PtestJvm=21` (identify the specific virtual-thread and profiling smoke-test modules under `dd-smoke-tests/`)
Expected: PASS.

- [ ] **Step 3: Manual attribution check**

With `DD_PROFILING_ENABLED=true` and `DD_PROFILING_DDPROF_ENABLED=true`, run a small app that does traced blocking work on virtual threads (many park/unpark cycles across carriers). Confirm in the profile that on-CPU/wall samples occurring inside the traced work are attributed to the expected span, and that carrier threads running *other* work are not mis-attributed to a virtual thread's span. Record the result in the PR description.

Note: this step is not automated because ddprof requires the native profiler library and a real profiling run; treat it as a required manual gate before marking the PR ready.

---

### Task 7: Full verification and PR prep

**Files:**
- No source change.

- [ ] **Step 1: Run the affected module test suites**

```bash
./gradlew :internal-api:test :dd-trace-core:test -PtestJvm=21
./gradlew :dd-java-agent:instrumentation:java:java-lang:java-lang-21.0:test -PtestJvm=21
./gradlew :dd-java-agent:instrumentation:java:java-concurrent:java-concurrent-21.0:test -PtestJvm=21
```
Expected: all PASS. (The java-concurrent run guards the TaskRunner/StructuredTaskScope paths that share the scope manager, even though they are unchanged.)

- [ ] **Step 2: Re-run the JMH benchmark to confirm the win**

Run: `./gradlew :dd-trace-core:jmh -Pjmh.includes=VirtualThreadContextBenchmark -PtestJvm=21 -Pjmh.profilers=gc`
Expected: `proposed*` rows show ~0 B/op; `currentCycle_*` rows show 176 B/op (profiling off) / 288 B/op (profiling on). Confirms the redesigned path is allocation-free.

- [ ] **Step 3: Spotless check**

Run: `./gradlew spotlessCheck`
Expected: PASS.

- [ ] **Step 4: Run `/techdebt` on the branch**

Per repo workflow, run `/techdebt` to check for duplication/complexity in the branch changes before opening the PR.

- [ ] **Step 5: Open a draft PR**

Title (imperative, user-visible): `Reduce virtual-thread context-propagation overhead on park/unpark`. Labels: `tag: ai generated`, `comp: core` (or the java-lang instrumentation label), a `type:` label. Body should summarize the seed-once redesign, cite the before/after JMH numbers, and record the Task 6 profiler-attribution result. Open as draft first.

---

## Self-Review

**Spec coverage:**
- Seed-once scope stack → Task 4 (`VirtualThreadState.onMount` first-mount `swap()`).
- Drop per-cycle swap → Task 4 (no swap-out on unmount; no swap-in on remount).
- Carrier-aware profiler rebind/unbind → Tasks 1–3 (capability flag + manager methods + tracer exposure), invoked in Task 4.
- No-op when profiling off / not carrier-bound → Task 1 flag + Task 2 `profilerCarrierBound` gate; verified in Task 2 second test and Task 3 first test.
- JFR must not rebind → Task 1 (JFR inherits default `false`); ddprof-only override.
- Continuation lifetime unchanged → Task 4 (`onTerminate` unchanged; constructor signature unchanged).
- Field-injection risk (spec risk #1) → resolved during planning (store is field-injected); no task needed, noted here.
- Profiler attribution validation (spec risk #2) → Task 6.
- Testing plan (existing suite green, migration test, benchmark guard) → Tasks 4, 5, 7.
- Out-of-scope TaskRunner/StructuredTaskScope untouched → confirmed; Task 7 step 1 runs their suite as a guard.

**Placeholder scan:** No TBD/TODO. Task 6 step 2 requires the executor to identify the specific smoke-test module path (the repo has many under `dd-smoke-tests/`); this is a lookup, not a design gap. Task 3 step 1 notes a visibility fallback for `NoopTracerAPI.INSTANCE`.

**Type consistency:** Method names consistent across tasks — `isCarrierThreadBound()` (Task 1 → gated in Task 2), `rebindProfilingContextToCarrier()` / `unbindProfilingContextFromCarrier()` (Task 2 manager → Task 3 tracer → Task 4 caller), `deactivateProfiling()` (Task 2 only). `VirtualThreadState(Context, Continuation)` constructor signature preserved so Task 4 needs no advice change.
