// Copyright 2026 Datadog, Inc.
package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.checkProcessSuccessfullyEnd;
import static datadog.smoketest.TaskBlockProfilingTestSupport.LOCAL_ROOT_SPAN_ID;
import static datadog.smoketest.TaskBlockProfilingTestSupport.SPAN_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.smoketest.profiling.ThreadSleepTaskBlockForkedApp;
import datadog.trace.test.util.Flaky;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;

/** End-to-end coverage for synchronous TaskBlock emission around {@code Thread.sleep}. */
@DisabledOnJ9
@Flaky(
    "TaskBlock/wall-clock sampler intermittently produces zero events across JDK versions; root cause is tracked separately")
final class ThreadSleepTaskBlockProfilingTest {
  private Path logFilePath;
  private Path dumpDir;

  @BeforeEach
  void setUp(TestInfo testInfo) throws IOException {
    logFilePath =
        TaskBlockProfilingTestSupport.buildLogFilePath(
            ThreadSleepTaskBlockProfilingTest.class, testInfo, "threadSleep");
    dumpDir = TaskBlockProfilingTestSupport.createDumpDir("dd-profiler-threadsleep-");
  }

  @AfterEach
  void cleanUp() throws IOException {
    TaskBlockProfilingTestSupport.deleteRecursively(dumpDir);
  }

  @Test
  void onlySpanlessPlatformSleepsEmitTaskBlocks() throws Exception {
    Process targetProcess =
        TaskBlockProfilingTestSupport.createTaskBlockProcessBuilder(
                "smoke-test-threadsleep-taskblock",
                ThreadSleepTaskBlockForkedApp.class.getName(),
                dumpDir,
                logFilePath)
            .start();

    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    Stats stats = new Stats();
    for (IItemCollection events : TaskBlockProfilingTestSupport.loadDumpedEvents(dumpDir)) {
      stats.add(events);
    }
    assertTrue(stats.spanlessPlatformCount > 0, "Expected spanless platform sleep TaskBlocks");
    assertEquals(0, stats.activePlatformCount, "Active-context sleeps must not emit TaskBlocks");
    assertEquals(0, stats.virtualCount, "Virtual-thread sleeps must not emit TaskBlocks");
    assertFalse(stats.spanlessHasContext, "Spanless sleep TaskBlocks must keep zero span context");
    assertFalse(stats.spanlessMissingThread, "Sleep TaskBlocks must resolve Event Thread");
    assertFalse(
        TaskBlockProfilingTestSupport.logContainsAny(
            logFilePath,
            "Failed to handle exception in instrumentation for "
                + ThreadSleepTaskBlockForkedApp.class.getName(),
            "Unexpected checked exception from ddprof TaskBlock hook"),
        "Thread.sleep TaskBlock instrumentation failed");
  }

  private static final class Stats {
    private long spanlessPlatformCount;
    private long activePlatformCount;
    private long virtualCount;
    private boolean spanlessHasContext;
    private boolean spanlessMissingThread;

    private void add(IItemCollection events) {
      for (IItemIterable items : events.apply(ItemFilters.type("datadog.TaskBlock"))) {
        TaskBlockProfilingTestSupport.assertFinalTaskBlockSchema(items);
        IMemberAccessor<String, IItem> threadAccessor =
            JdkAttributes.EVENT_THREAD_NAME.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> spanAccessor = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> rootAccessor =
            LOCAL_ROOT_SPAN_ID.getAccessor(items.getType());
        for (IItem item : items) {
          String thread = threadAccessor == null ? null : threadAccessor.getMember(item);
          if (ThreadSleepTaskBlockForkedApp.SPANLESS_PLATFORM_THREAD.equals(thread)) {
            spanlessPlatformCount++;
            spanlessHasContext |=
                spanAccessor.getMember(item).longValue() != 0L
                    || rootAccessor.getMember(item).longValue() != 0L;
            spanlessMissingThread |= thread == null || thread.isEmpty();
          } else if (ThreadSleepTaskBlockForkedApp.ACTIVE_PLATFORM_THREAD.equals(thread)) {
            activePlatformCount++;
          } else if (ThreadSleepTaskBlockForkedApp.VIRTUAL_THREAD.equals(thread)) {
            virtualCount++;
          }
        }
      }
    }
  }
}
