// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.locksupport;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@WithConfig(key = PROFILING_ENABLED, value = "true")
@WithConfig(key = PROFILING_DATADOG_PROFILER_ENABLED, value = "true")
@WithConfig(key = PROFILING_DATADOG_PROFILER_WALL_ENABLED, value = "true")
@WithConfig(key = PROFILING_DATADOG_PROFILER_WALL_PRECHECK, value = "true")
@WithConfig(key = PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER, value = "false")
class LockSupportProfilingInstrumentationForkedTest extends AbstractInstrumentationTest {
  /** Bounded so an unpark that is somehow missed fails the assertion instead of hanging. */
  private static final long PARK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

  @BeforeEach
  void clearProfilingContextIntegration() {
    testProfilingContextIntegration.clear();
  }

  @AfterEach
  void resetProfilingContextIntegration() {
    testProfilingContextIntegration.clear();
  }

  @Test
  void transformedParkWithBlockerDispatchesOneBalancedLifecycleOnTheCallingThread() {
    Object blocker = new Object();
    Thread callingThread = Thread.currentThread();

    LockSupport.parkNanos(blocker, TimeUnit.MILLISECONDS.toNanos(1));

    assertEquals(1, testProfilingContextIntegration.getAcceptedParkEnterCalls(callingThread));
    assertEquals(1, testProfilingContextIntegration.getParkExitCalls(callingThread));
    assertTrue(testProfilingContextIntegration.getParkExitThreads().contains(callingThread));
    assertEquals(
        Integer.toUnsignedLong(System.identityHashCode(blocker)),
        testProfilingContextIntegration.getLastParkBlocker().get());
  }

  @Test
  void transformedParkWithoutBlockerDispatchesAZeroBlocker() {
    Thread callingThread = Thread.currentThread();

    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));

    assertTrue(testProfilingContextIntegration.getParkExitThreads().contains(callingThread));
    assertEquals(0L, testProfilingContextIntegration.getLastParkBlocker().get());
  }

  @Test
  void delegatedParkUntilWithBlockerDispatchesOneLifecycle() {
    Object blocker = new Object();
    Thread callingThread = Thread.currentThread();

    LockSupport.parkUntil(blocker, System.currentTimeMillis() + 50L);

    assertEquals(1, testProfilingContextIntegration.getAcceptedParkEnterCalls(callingThread));
    assertEquals(1, testProfilingContextIntegration.getParkExitCalls(callingThread));
    assertEquals(
        Integer.toUnsignedLong(System.identityHashCode(blocker)),
        testProfilingContextIntegration.getLastParkBlocker().get());
  }

  @Test
  void nonPositiveParkNanosDoesNotDispatchLifecycle() {
    Thread callingThread = Thread.currentThread();

    LockSupport.parkNanos(0L);
    LockSupport.parkNanos(new Object(), -1L);

    assertEquals(0, testProfilingContextIntegration.getParkEnterCalls(callingThread));
    assertEquals(0, testProfilingContextIntegration.getParkExitCalls(callingThread));
  }

  @Test
  void tracedUnparkIsAttributedToTheParkExitOfTheTargetThread() throws Exception {
    Thread worker =
        new Thread(() -> LockSupport.parkNanos(PARK_TIMEOUT_NANOS), "locksupport-park-target");
    worker.start();

    AgentSpan span = tracer.startSpan("test", "locksupport.unparker");
    long spanId;
    try (AgentScope ignored = tracer.activateSpan(span)) {
      spanId = span.getSpanId();
      // Recorded against the target thread regardless of whether it has parked yet.
      LockSupport.unpark(worker);
    } finally {
      span.finish();
    }
    worker.join(TimeUnit.SECONDS.toMillis(10));

    assertFalse(worker.isAlive(), "Parked worker was not released");
    assertEquals(spanId, testProfilingContextIntegration.getLastUnblockingSpanId(worker));
  }

  @Test
  void untrackedUnparkAttributionIsSkippedWhenTheProfilerIsNotRecording() throws Exception {
    testProfilingContextIntegration.setUnparkAttributionEnabled(false);
    Thread worker =
        new Thread(() -> LockSupport.parkNanos(PARK_TIMEOUT_NANOS), "locksupport-park-untracked");
    worker.start();

    AgentSpan span = tracer.startSpan("test", "locksupport.unparker");
    try (AgentScope ignored = tracer.activateSpan(span)) {
      LockSupport.unpark(worker);
    } finally {
      span.finish();
    }
    worker.join(TimeUnit.SECONDS.toMillis(10));

    assertFalse(worker.isAlive(), "Parked worker was not released");
    assertEquals(0L, testProfilingContextIntegration.getLastUnblockingSpanId(worker));
  }

  @Test
  void rejectedEntryIsNotFollowedByAnExit() {
    testProfilingContextIntegration.setAcceptParkEntries(false);
    Thread callingThread = Thread.currentThread();

    LockSupport.parkNanos(1L);

    assertEquals(1, testProfilingContextIntegration.getParkEnterCalls(callingThread));
    assertFalse(testProfilingContextIntegration.getParkExitThreads().contains(callingThread));
  }
}
