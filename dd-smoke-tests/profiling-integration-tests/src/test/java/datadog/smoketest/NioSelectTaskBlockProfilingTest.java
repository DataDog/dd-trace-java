// Copyright 2026 Datadog, Inc.
package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.checkProcessSuccessfullyEnd;
import static datadog.smoketest.TaskBlockProfilingTestSupport.BLOCKER;
import static datadog.smoketest.TaskBlockProfilingTestSupport.LOCAL_ROOT_SPAN_ID;
import static datadog.smoketest.TaskBlockProfilingTestSupport.SPAN_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.smoketest.profiling.NioSelectTaskBlockForkedApp;
import datadog.trace.test.util.Flaky;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;

/** Smoke coverage for TaskBlocks emitted around Netty's NIO event loop Selector.select wait. */
@DisabledOnJ9
@EnabledOnOs(OS.LINUX)
@Flaky(
    "TaskBlock/wall-clock sampler intermittently produces zero events across JDK versions; root cause is tracked separately")
final class NioSelectTaskBlockProfilingTest {
  private Path dumpDir;
  private Path logFilePath;

  @BeforeEach
  void setup(TestInfo testInfo) throws IOException {
    logFilePath =
        TaskBlockProfilingTestSupport.buildLogFilePath(
            NioSelectTaskBlockProfilingTest.class, testInfo, "nioSelect");
    dumpDir = TaskBlockProfilingTestSupport.createDumpDir("dd-profiler-nioselect-");
  }

  @AfterEach
  void tearDown() throws IOException {
    TaskBlockProfilingTestSupport.deleteRecursively(dumpDir);
  }

  @Test
  void idleNettyNioEventLoopEmitsSpanlessTaskBlocks() throws Exception {
    Process targetProcess =
        TaskBlockProfilingTestSupport.createTaskBlockProcessBuilder(
                "smoke-test-netty-nio-select-taskblock",
                NioSelectTaskBlockForkedApp.class.getName(),
                dumpDir,
                logFilePath)
            .start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    JfrStats stats = new JfrStats();
    for (IItemCollection events : TaskBlockProfilingTestSupport.loadDumpedEvents(dumpDir)) {
      stats.add(events);
    }

    assertTrue(stats.count > 0, "Expected TaskBlocks from the idle Netty NIO event loop");
    assertEquals(0, stats.nonZeroBlockerCount, "Blocker attribution is out of scope; expected 0");
    assertFalse(stats.hasNonZeroSpanId, "Spanless select TaskBlocks must keep spanId zero");
    assertFalse(
        stats.hasNonZeroLocalRootSpanId, "Spanless select TaskBlocks must keep root spanId zero");
    assertFalse(stats.hasMissingEventThread, "TaskBlock events must resolve Event Thread");
    assertFalse(
        TaskBlockProfilingTestSupport.logContainsAny(
            logFilePath, "NoClassDefFoundError", "Failed to handle exception"),
        "Netty NIO select TaskBlock instrumentation failed");
  }

  private static final class JfrStats {
    long count;
    long nonZeroBlockerCount;
    boolean hasNonZeroSpanId;
    boolean hasNonZeroLocalRootSpanId;
    boolean hasMissingEventThread;

    void add(IItemCollection events) {
      for (IItemIterable items : events.apply(ItemFilters.type("datadog.TaskBlock"))) {
        TaskBlockProfilingTestSupport.assertFinalTaskBlockSchema(items);
        IMemberAccessor<IQuantity, IItem> spanId = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> rootSpanId =
            LOCAL_ROOT_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> blocker = BLOCKER.getAccessor(items.getType());
        IMemberAccessor<String, IItem> eventThread =
            JdkAttributes.EVENT_THREAD_NAME.getAccessor(items.getType());
        for (IItem item : items) {
          String thread = eventThread.getMember(item);
          if (!NioSelectTaskBlockForkedApp.EVENT_LOOP_THREAD.equals(thread)) {
            continue;
          }
          count++;
          nonZeroBlockerCount += blocker.getMember(item).longValue() == 0 ? 0 : 1;
          hasNonZeroSpanId |= spanId.getMember(item).longValue() != 0;
          hasNonZeroLocalRootSpanId |= rootSpanId.getMember(item).longValue() != 0;
          hasMissingEventThread |= thread.isEmpty();
        }
      }
    }
  }
}
