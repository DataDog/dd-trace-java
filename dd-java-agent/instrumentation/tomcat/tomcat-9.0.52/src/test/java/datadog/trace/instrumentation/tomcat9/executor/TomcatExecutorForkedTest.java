package datadog.trace.instrumentation.tomcat9.executor;

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.TestProfilingContextIntegration;
import datadog.trace.agent.test.TestProfilingContextIntegration.TestQueueTiming;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import org.apache.tomcat.util.threads.TaskQueue;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@WithConfig(key = PROFILING_ENABLED, value = "true")
@WithConfig(key = PROFILING_QUEUEING_TIME_ENABLED, value = "true")
class TomcatExecutorForkedTest extends AbstractInstrumentationTest {
  private static TestProfilingContextIntegration profiling;

  @BeforeAll
  static void installProfilingTracer() {
    tracer.close();
    writer = new ListWriter();
    profiling = new TestProfilingContextIntegration();
    CoreTracer profilingTracer =
        CoreTracer.builder()
            .writer(writer)
            .strictTraceWrites(true)
            .profilingContextIntegration(profiling)
            .build();
    TracerInstaller.forceInstallGlobalTracer(profilingTracer);
    tracer = profilingTracer;
    InstrumentationBasedProfiling.enableInstrumentationBasedProfiling();
  }

  @AfterEach
  void clearTimings() {
    profiling.getClosedTimings().clear();
  }

  @Test
  void recordsQueueTimingWithSubmit() {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 5, SECONDS, new TaskQueue());
    try {
      runUnderTrace(
          "parent",
          () -> {
            executor.submit(new TestRunnable()).get(5, SECONDS);
            return null;
          });
      verifyTiming();
    } finally {
      executor.shutdown();
    }
  }

  @Test
  void closesQueueTimingForRejectedTasks() {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 5, SECONDS, new TaskQueue());
    executor.shutdown();

    assertThrows(
        RejectedExecutionException.class,
        () ->
            runUnderTrace(
                "parent",
                () -> {
                  executor.execute(new TestRunnable());
                  return null;
                }));
    assertTrue(profiling.isBalanced());
    assertFalse(profiling.getClosedTimings().isEmpty());
    profiling.getClosedTimings().clear();

    assertThrows(
        RejectedExecutionException.class,
        () ->
            runUnderTrace(
                "parent",
                () -> {
                  executor.submit(new TestRunnable());
                  return null;
                }));
    assertTrue(profiling.isBalanced());
    assertFalse(profiling.getClosedTimings().isEmpty());
  }

  @Test
  void closesQueueTimingForRemovedTasks() throws Exception {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 5, SECONDS, new TaskQueue());
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      executor.execute(
          () -> {
            started.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      assertTrue(started.await(5, SECONDS));
      TestRunnable task = new TestRunnable();
      runUnderTrace(
          "parent",
          () -> {
            executor.execute(task);
            return null;
          });

      assertTrue(executor.remove(task));
      assertTrue(profiling.isBalanced());
      assertFalse(profiling.getClosedTimings().isEmpty());
    } finally {
      release.countDown();
      executor.shutdown();
    }
  }

  private static void verifyTiming() {
    assertTrue(profiling.isBalanced());
    assertFalse(profiling.getClosedTimings().isEmpty());
    int checked = 0;
    Timing timing;
    while ((timing = profiling.getClosedTimings().pollFirst()) != null) {
      TestQueueTiming queueTiming = (TestQueueTiming) timing;
      if (!queueTiming.getTask().getSimpleName().isEmpty()) {
        assertEquals("TestRunnable", queueTiming.getTask().getSimpleName());
        assertNotNull(queueTiming.getScheduler());
        assertEquals(Thread.currentThread(), queueTiming.getOrigin());
        assertTrue(queueTiming.getQueueLength() >= 0);
        assertEquals(TaskQueue.class, queueTiming.getQueue());
        checked++;
      }
    }
    assertTrue(checked > 0);
  }

  static class TestRunnable implements Runnable {
    @Override
    public void run() {}
  }
}
