// Copyright 2026 Datadog, Inc.
package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.checkProcessSuccessfullyEnd;
import static datadog.smoketest.TaskBlockProfilingTestSupport.BLOCKER;
import static datadog.smoketest.TaskBlockProfilingTestSupport.LOCAL_ROOT_SPAN_ID;
import static datadog.smoketest.TaskBlockProfilingTestSupport.SPAN_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.jmc.common.item.Attribute.attr;
import static org.openjdk.jmc.common.unit.UnitLookup.NUMBER;

import com.datadog.smoketest.profiling.LockSupportTaskBlockForkedApp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;

@DisabledOnJ9
@EnabledOnOs(OS.LINUX)
final class LockSupportTaskBlockProfilingTest {
  private static final IAttribute<IQuantity> UNBLOCKING_SPAN_ID =
      attr("unblockingSpanId", "unblockingSpanId", "unblockingSpanId", NUMBER);

  private Path logFilePath;
  private Path dumpDir;

  @BeforeEach
  void setUp(TestInfo testInfo) throws IOException {
    logFilePath =
        TaskBlockProfilingTestSupport.buildLogFilePath(
            LockSupportTaskBlockProfilingTest.class, testInfo, "lockSupport");
    dumpDir = TaskBlockProfilingTestSupport.createDumpDir("dd-profiler-locksupport-");
  }

  @AfterEach
  void cleanUp() throws IOException {
    TaskBlockProfilingTestSupport.deleteRecursively(dumpDir);
  }

  @Test
  void onlySpanlessPlatformParksEmitTaskBlocks() throws Exception {
    Process targetProcess =
        TaskBlockProfilingTestSupport.createTaskBlockProcessBuilder(
                "smoke-test-locksupport-taskblock",
                LockSupportTaskBlockForkedApp.class.getName(),
                dumpDir,
                logFilePath)
            .start();

    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    String log = new String(Files.readAllBytes(logFilePath), StandardCharsets.UTF_8);
    Stats stats =
        new Stats(
            markerValue(log, LockSupportTaskBlockForkedApp.SPANLESS_BLOCKER_MARKER),
            markerValue(log, LockSupportTaskBlockForkedApp.ACTIVE_BLOCKER_MARKER),
            markerValue(log, LockSupportTaskBlockForkedApp.VIRTUAL_BLOCKER_MARKER));
    for (IItemCollection events : TaskBlockProfilingTestSupport.loadDumpedEvents(dumpDir)) {
      stats.add(events);
    }
    assertTrue(stats.spanlessPlatformCount > 0, "Expected spanless platform park TaskBlocks");
    assertEquals(0, stats.activePlatformCount, "Active-context parks must not emit TaskBlocks");
    assertEquals(0, stats.virtualCount, "Virtual-thread parks must not emit TaskBlocks");
    assertFalse(stats.spanlessHasContext, "Spanless park TaskBlocks must keep zero span context");
    assertTrue(stats.spanlessWithBlocker > 0, "Expected blocker identity on park TaskBlocks");
    assertTrue(
        stats.spanlessWithUnblockingSpan > 0, "Expected best-effort unpark span attribution");
    assertFalse(stats.spanlessMissingThread, "TaskBlock events must resolve Event Thread");
    assertFalse(
        TaskBlockProfilingTestSupport.logContainsAny(
            logFilePath,
            "Failed to handle exception in instrumentation for java.util.concurrent.locks.LockSupport",
            "Unexpected checked exception from ddprof TaskBlock hook"),
        "LockSupport TaskBlock instrumentation failed");
  }

  private static long markerValue(String log, String marker) {
    int start = log.indexOf(marker);
    assertTrue(start >= 0, "Missing workload marker " + marker);
    start += marker.length();
    int end = start;
    while (end < log.length() && Character.isDigit(log.charAt(end))) {
      end++;
    }
    return Long.parseLong(log.substring(start, end));
  }

  private static final class Stats {
    private final long spanlessBlocker;
    private final long activeBlocker;
    private final long virtualBlocker;
    private long spanlessPlatformCount;
    private long activePlatformCount;
    private long virtualCount;
    private long spanlessWithBlocker;
    private long spanlessWithUnblockingSpan;
    private boolean spanlessHasContext;
    private boolean spanlessMissingThread;

    private Stats(long spanlessBlocker, long activeBlocker, long virtualBlocker) {
      this.spanlessBlocker = spanlessBlocker;
      this.activeBlocker = activeBlocker;
      this.virtualBlocker = virtualBlocker;
    }

    private void add(IItemCollection events) {
      for (IItemIterable items : events.apply(ItemFilters.type("datadog.TaskBlock"))) {
        TaskBlockProfilingTestSupport.assertFinalTaskBlockSchema(items);
        IMemberAccessor<String, IItem> threadAccessor =
            JdkAttributes.EVENT_THREAD_NAME.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> spanAccessor = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> rootAccessor =
            LOCAL_ROOT_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> blockerAccessor = BLOCKER.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> unblockingAccessor =
            UNBLOCKING_SPAN_ID.getAccessor(items.getType());
        for (IItem item : items) {
          String thread = threadAccessor == null ? null : threadAccessor.getMember(item);
          long blocker = blockerAccessor.getMember(item).longValue();
          if (blocker == spanlessBlocker) {
            spanlessPlatformCount++;
            spanlessHasContext |=
                spanAccessor.getMember(item).longValue() != 0L
                    || rootAccessor.getMember(item).longValue() != 0L;
            spanlessWithBlocker++;
            spanlessWithUnblockingSpan +=
                unblockingAccessor.getMember(item).longValue() == 0L ? 0 : 1;
            spanlessMissingThread |=
                thread == null
                    || thread.isEmpty()
                    || !LockSupportTaskBlockForkedApp.SPANLESS_PLATFORM_THREAD.equals(thread);
          } else if (blocker == activeBlocker) {
            activePlatformCount++;
          } else if (blocker == virtualBlocker) {
            virtualCount++;
          }
        }
      }
    }
  }
}
