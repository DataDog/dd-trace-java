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

    assertTrue(testProfilingContextIntegration.getParkEnterCalls().get() >= 1);
    assertTrue(testProfilingContextIntegration.getParkExitCalls().get() >= 1);
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
  void rejectedEntryIsNotFollowedByAnExit() {
    testProfilingContextIntegration.setAcceptParkEntries(false);
    Thread callingThread = Thread.currentThread();

    LockSupport.parkNanos(1L);

    assertTrue(testProfilingContextIntegration.getParkEnterCalls().get() >= 1);
    assertFalse(testProfilingContextIntegration.getParkExitThreads().contains(callingThread));
  }
}
