// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.locksupport;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_TASKBLOCK_VIRTUAL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.environment.ThreadSupport;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.TestProfilingContextIntegration.VirtualParkExit;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
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
@WithConfig(key = PROFILING_DATADOG_PROFILER_WALL_TASKBLOCK_VIRTUAL_ENABLED, value = "true")
class LockSupportVirtualParkForkedTest extends AbstractInstrumentationTest {
  private static final long PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

  /** Parks while the JVM runs its static initializer, a native frame that pins a virtual thread. */
  static final class ParksInClassInitializer {
    static {
      LockSupport.parkNanos(PARK_NANOS);
    }

    static void touch() {}
  }

  @BeforeEach
  void clearProfilingContextIntegration() {
    assumeTrue(ThreadSupport.supportsVirtualThreads(), "Virtual threads are unavailable");
    testProfilingContextIntegration.clear();
  }

  @AfterEach
  void resetProfilingContextIntegration() {
    testProfilingContextIntegration.clear();
  }

  @Test
  void longVirtualParkIsReportedOnceWithoutPlatformEntry() throws Exception {
    Object blocker = new Object();
    Thread thread =
        onVirtualThread(() -> {
          LockSupport.parkNanos(blocker, PARK_NANOS);
          return Thread.currentThread();
        });

    List<VirtualParkExit> exits = testProfilingContextIntegration.getVirtualParkExits();
    assertEquals(1, exits.size());
    assertEquals(thread, exits.get(0).thread);
    assertTrue(exits.get(0).elapsedNanos >= PARK_NANOS, "elapsed " + exits.get(0).elapsedNanos);
    assertEquals(Integer.toUnsignedLong(System.identityHashCode(blocker)), exits.get(0).blocker);
    assertEquals(0L, exits.get(0).unblockingSpanId);
    assertEquals(0, testProfilingContextIntegration.getParkEnterCalls(thread));
    assertEquals(0, testProfilingContextIntegration.getParkExitCalls(thread));
  }

  @Test
  void everyParkVariantIsReportedOnVirtualThreads() throws Exception {
    // The JDK may re-park internally, so each variant must add at least one report rather than
    // exactly one.
    List<Runnable> variants = new ArrayList<>();
    variants.add(() -> LockSupport.parkNanos(PARK_NANOS));
    variants.add(() -> LockSupport.parkNanos(new Object(), PARK_NANOS));
    variants.add(() -> LockSupport.parkUntil(System.currentTimeMillis() + 20L));
    variants.add(() -> LockSupport.parkUntil(new Object(), System.currentTimeMillis() + 20L));
    variants.add(() -> {
      unparkLater(Thread.currentThread());
      LockSupport.park();
    });
    variants.add(() -> {
      unparkLater(Thread.currentThread());
      LockSupport.park(new Object());
    });
    int[] reportsAfter =
        onVirtualThread(() -> {
          int[] marks = new int[variants.size()];
          for (int i = 0; i < marks.length; i++) {
            variants.get(i).run();
            marks[i] = testProfilingContextIntegration.getVirtualParkExits().size();
          }
          return marks;
        });

    int previous = 0;
    for (int i = 0; i < reportsAfter.length; i++) {
      assertTrue(reportsAfter[i] > previous, "park variant " + i + " was not reported");
      previous = reportsAfter[i];
    }
  }

  @Test
  void shortVirtualParkIsNotReported() throws Exception {
    onVirtualThread(() -> {
      LockSupport.parkNanos(1L);
      return null;
    });

    assertTrue(testProfilingContextIntegration.getVirtualParkExits().isEmpty());
  }

  @Test
  void nonPositiveVirtualParkIsNotReported() throws Exception {
    onVirtualThread(() -> {
      LockSupport.parkNanos(0L);
      LockSupport.parkUntil(0L);
      return null;
    });

    assertTrue(testProfilingContextIntegration.getVirtualParkExits().isEmpty());
  }

  @Test
  void virtualParkIsNotReportedWhenTheProfilerDisablesIt() throws Exception {
    testProfilingContextIntegration.setVirtualParkEnabled(false);

    Thread thread =
        onVirtualThread(() -> {
          LockSupport.parkNanos(PARK_NANOS);
          return Thread.currentThread();
        });

    assertTrue(testProfilingContextIntegration.getVirtualParkExits().isEmpty());
    // Flag off keeps the previous behavior: the platform hook is still consulted.
    assertEquals(1, testProfilingContextIntegration.getParkEnterCalls(thread));
  }

  @Test
  void pinnedVirtualParkIsNotReported() throws Exception {
    onVirtualThread(() -> {
      ParksInClassInitializer.touch();
      return null;
    });

    assertTrue(testProfilingContextIntegration.getVirtualParkExits().isEmpty());
  }

  @Test
  void platformParkKeepsItsLifecycleAndIsNeverReportedAsVirtual() {
    Thread callingThread = Thread.currentThread();

    LockSupport.parkNanos(PARK_NANOS);

    assertEquals(1, testProfilingContextIntegration.getAcceptedParkEnterCalls(callingThread));
    assertEquals(1, testProfilingContextIntegration.getParkExitCalls(callingThread));
    assertTrue(testProfilingContextIntegration.getVirtualParkExits().isEmpty());
  }

  private static void unparkLater(Thread target) {
    Thread unparker =
        new Thread(() -> {
          LockSupport.parkNanos(PARK_NANOS);
          LockSupport.unpark(target);
        });
    unparker.setDaemon(true);
    unparker.start();
  }

  private static <T> T onVirtualThread(Callable<T> task) throws Exception {
    ExecutorService executor = ThreadSupport.newVirtualThreadPerTaskExecutor().get();
    try {
      return executor.submit(task).get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdown();
    }
  }
}
