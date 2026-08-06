// Copyright 2026 Datadog, Inc.
package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.checkProcessSuccessfullyEnd;
import static datadog.smoketest.TaskBlockProfilingTestSupport.BLOCKER;
import static datadog.smoketest.TaskBlockProfilingTestSupport.LOCAL_ROOT_SPAN_ID;
import static datadog.smoketest.TaskBlockProfilingTestSupport.SPAN_ID;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
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

/** Smoke coverage for native synchronized-contention TaskBlock emission. */
@DisabledOnJ9
@EnabledOnOs(OS.LINUX)
final class SynchronizedContentionProfilingTest {
  private Path dumpDir;
  private Path logFilePath;

  @BeforeEach
  void setup(TestInfo testInfo) throws IOException {
    logFilePath =
        TaskBlockProfilingTestSupport.buildLogFilePath(
            SynchronizedContentionProfilingTest.class, testInfo, "syncContention");
    dumpDir = TaskBlockProfilingTestSupport.createDumpDir("dd-profiler-synccontention-");
  }

  @AfterEach
  void tearDown() throws IOException {
    TaskBlockProfilingTestSupport.deleteRecursively(dumpDir);
  }

  @Test
  void spanlessSynchronizedContentionEmitsNativeTaskBlocks() throws Exception {
    Process targetProcess =
        TaskBlockProfilingTestSupport.createTaskBlockProcessBuilder(
                "smoke-test-synccontention-taskblock",
                com.datadog.smoketest.profiling.SynchronizedContentionForkedApp.class.getName(),
                dumpDir,
                logFilePath)
            .start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    JfrStats stats = new JfrStats();
    for (IItemCollection events : TaskBlockProfilingTestSupport.loadDumpedEvents(dumpDir)) {
      stats.add(events);
    }

    assertTrue(stats.blockScenarioCount > 0, "Expected synchronized-block TaskBlocks");
    assertTrue(stats.instanceMethodScenarioCount > 0, "Expected instance-method TaskBlocks");
    assertTrue(stats.staticMethodScenarioCount > 0, "Expected static-method TaskBlocks");
    assertFalse(stats.hasNonZeroSpanId, "Spanless TaskBlocks must keep spanId zero");
    assertFalse(stats.hasNonZeroLocalRootSpanId, "Spanless TaskBlocks must keep root spanId zero");
    assertTrue(stats.blockersWithNonZeroValue > 0, "Expected monitor identity on TaskBlocks");
    assertTrue(stats.distinctBlockerValues.size() > 1, "Expected per-monitor blocker identities");
    assertFalse(stats.hasMissingEventThread, "TaskBlock events must resolve Event Thread");
    assertFalse(
        TaskBlockProfilingTestSupport.logContainsAny(
            logFilePath, "NoClassDefFoundError", "Failed to handle exception", "VerifyError"),
        "native synchronized-contention TaskBlock path failed");
  }

  private static final class JfrStats {
    long blockScenarioCount;
    long instanceMethodScenarioCount;
    long staticMethodScenarioCount;
    long blockersWithNonZeroValue;
    final Set<Long> distinctBlockerValues = new HashSet<>();
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
          if ("sync-block-spanless".equals(thread)) {
            blockScenarioCount++;
          } else if ("sync-instance-spanless".equals(thread)) {
            instanceMethodScenarioCount++;
          } else if ("sync-static-spanless".equals(thread)) {
            staticMethodScenarioCount++;
          } else {
            continue;
          }
          hasNonZeroSpanId |= spanId.getMember(item).longValue() != 0;
          hasNonZeroLocalRootSpanId |= rootSpanId.getMember(item).longValue() != 0;
          long blockerValue = blocker.getMember(item).longValue();
          if (blockerValue != 0) {
            blockersWithNonZeroValue++;
            distinctBlockerValues.add(blockerValue);
          }
          hasMissingEventThread |= thread.isEmpty();
        }
      }
    }
  }
}
