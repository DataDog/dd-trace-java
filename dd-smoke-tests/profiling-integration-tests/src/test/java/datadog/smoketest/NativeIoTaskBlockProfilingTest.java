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

/** Linux smoke coverage for TaskBlocks emitted by native socket-I/O interposition. */
@DisabledOnJ9
@EnabledOnOs(OS.LINUX)
final class NativeIoTaskBlockProfilingTest {
  private Path dumpDir;
  private Path logFilePath;

  @BeforeEach
  void setup(TestInfo testInfo) throws IOException {
    logFilePath =
        TaskBlockProfilingTestSupport.buildLogFilePath(
            NativeIoTaskBlockProfilingTest.class, testInfo, "nativeIo");
    dumpDir = TaskBlockProfilingTestSupport.createDumpDir("dd-profiler-nativeio-");
  }

  @AfterEach
  void tearDown() throws IOException {
    TaskBlockProfilingTestSupport.deleteRecursively(dumpDir);
  }

  @Test
  void blockingNativeSocketIoEmitsSpanlessTaskBlocks() throws Exception {
    Process targetProcess =
        TaskBlockProfilingTestSupport.createTaskBlockProcessBuilder(
                "smoke-test-nativeio-taskblock",
                com.datadog.smoketest.profiling.NativeIoTaskBlockForkedApp.class.getName(),
                dumpDir,
                logFilePath)
            .start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    JfrStats stats = new JfrStats();
    for (IItemCollection events : TaskBlockProfilingTestSupport.loadDumpedEvents(dumpDir)) {
      stats.add(events);
    }

    assertTrue(stats.count > 0, "Expected TaskBlocks from blocking native socket I/O");
    assertTrue(stats.nonZeroBlockerCount > 0, "Expected encoded native I/O blocker identity");
    assertFalse(stats.hasNonZeroSpanId, "Spanless native I/O TaskBlocks must keep spanId zero");
    assertFalse(
        stats.hasNonZeroLocalRootSpanId,
        "Spanless native I/O TaskBlocks must keep root spanId zero");
    assertFalse(stats.hasMissingEventThread, "TaskBlock events must resolve Event Thread");
    assertFalse(
        TaskBlockProfilingTestSupport.logContainsAny(
            logFilePath,
            "native I/O hooks disabled",
            "NoClassDefFoundError",
            "Failed to handle exception"),
        "native I/O TaskBlock path failed");
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
          if (!"native-io-spanless".equals(thread)) {
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
