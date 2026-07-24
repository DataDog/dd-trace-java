// Copyright 2026 Datadog, Inc.
package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.checkProcessSuccessfullyEnd;
import static datadog.smoketest.TaskBlockProfilingTestSupport.BLOCKER;
import static datadog.smoketest.TaskBlockProfilingTestSupport.LOCAL_ROOT_SPAN_ID;
import static datadog.smoketest.TaskBlockProfilingTestSupport.SPAN_ID;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openjdk.jmc.common.item.Attribute.attr;
import static org.openjdk.jmc.common.unit.UnitLookup.NUMBER;

import datadog.trace.api.Trace;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.openjdk.jmc.common.IMCFrame;
import org.openjdk.jmc.common.IMCStackTrace;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.JfrAttributes;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;

/** Smoke coverage for native {@code Object.wait} TaskBlock emission and context exclusion. */
@DisabledOnJ9
@EnabledOnOs(OS.LINUX)
final class ObjectWaitTaskBlockProfilingTest {
  private static final IAttribute<IQuantity> UNBLOCKING_SPAN_ID =
      attr("unblockingSpanId", "unblockingSpanId", "unblockingSpanId", NUMBER);

  private Path dumpDir;
  private Path logFilePath;

  @BeforeEach
  void setup(TestInfo testInfo) throws IOException {
    logFilePath =
        TaskBlockProfilingTestSupport.buildLogFilePath(
            ObjectWaitTaskBlockProfilingTest.class, testInfo, "objectWait");
    dumpDir = TaskBlockProfilingTestSupport.createDumpDir("dd-profiler-objectwait-");
  }

  @AfterEach
  void tearDown() throws IOException {
    TaskBlockProfilingTestSupport.deleteRecursively(dumpDir);
  }

  @Test
  void spanlessObjectWaitEmitsWhileTracedObjectWaitDoesNot() throws Exception {
    Process targetProcess =
        TaskBlockProfilingTestSupport.createTaskBlockProcessBuilder(
                "smoke-test-objectwait-taskblock",
                ObjectWaitTaskBlockForkedApp.class.getName(),
                dumpDir,
                logFilePath)
            .start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);

    JfrStats stats = new JfrStats();
    for (IItemCollection events : TaskBlockProfilingTestSupport.loadDumpedEvents(dumpDir)) {
      stats.add(events);
    }

    assertTrue(stats.spanlessCount > 0, "Expected Object.wait TaskBlocks outside trace context");
    assertTrue(stats.nonZeroBlockerCount > 0, "Expected monitor identity on Object.wait events");
    assertFalse(stats.hasNonZeroSpanId, "Spanless TaskBlocks must keep spanId zero");
    assertFalse(stats.hasNonZeroLocalRootSpanId, "Spanless TaskBlocks must keep root spanId zero");
    assertFalse(stats.hasNonZeroUnblockingSpanId, "Object.wait has no attributed notifier hook");
    assertFalse(stats.hasMissingEventThread, "TaskBlock events must resolve Event Thread");
    assertFalse(
        stats.hasTracedWaitEvent,
        "Traced waits must not emit TaskBlock events: " + stats.tracedWaitStacks);
    assertFalse(
        TaskBlockProfilingTestSupport.logContainsAny(
            logFilePath, "NoClassDefFoundError", "Failed to handle exception in instrumentation"),
        "Object.wait TaskBlock path failed");
  }

  public static final class ObjectWaitTaskBlockForkedApp {
    private static final int ITERATIONS = 20;
    private static final Object BLOCKER = new Object();

    public static void main(String[] args) throws Exception {
      Thread spanless =
          new Thread(ObjectWaitTaskBlockForkedApp::waitRepeatedly, "objectwait-spanless");
      spanless.start();
      spanless.join();

      Thread traced =
          new Thread(ObjectWaitTaskBlockForkedApp::waitRepeatedlyWithTrace, "objectwait-traced");
      traced.start();
      traced.join();
      Thread.sleep(1500);
    }

    private static void waitRepeatedly() {
      try {
        for (int i = 0; i < ITERATIONS; i++) {
          synchronized (BLOCKER) {
            BLOCKER.wait(50L);
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }

    @Trace
    private static void waitRepeatedlyWithTrace() {
      waitRepeatedly();
    }
  }

  private static final class JfrStats {
    long spanlessCount;
    long nonZeroBlockerCount;
    boolean hasNonZeroSpanId;
    boolean hasNonZeroLocalRootSpanId;
    boolean hasNonZeroUnblockingSpanId;
    boolean hasMissingEventThread;
    boolean hasTracedWaitEvent;
    final List<String> tracedWaitStacks = new ArrayList<>();

    void add(IItemCollection events) {
      for (IItemIterable items : events.apply(ItemFilters.type("datadog.TaskBlock"))) {
        TaskBlockProfilingTestSupport.assertFinalTaskBlockSchema(items);
        IMemberAccessor<IQuantity, IItem> spanId = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> rootSpanId =
            LOCAL_ROOT_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> blocker = BLOCKER.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> unblockingSpanId =
            UNBLOCKING_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<String, IItem> eventThread =
            JdkAttributes.EVENT_THREAD_NAME.getAccessor(items.getType());
        IMemberAccessor<IMCStackTrace, IItem> stackTrace =
            JfrAttributes.EVENT_STACKTRACE.getAccessor(items.getType());
        for (IItem item : items) {
          String thread = eventThread.getMember(item);
          if ("objectwait-traced".equals(thread)) {
            IMCStackTrace trace = stackTrace.getMember(item);
            for (IMCFrame frame : trace.getFrames()) {
              if (ObjectWaitTaskBlockForkedApp.class
                      .getName()
                      .equals(frame.getMethod().getType().getFullName())
                  && "waitRepeatedly".equals(frame.getMethod().getMethodName())) {
                hasTracedWaitEvent = true;
                tracedWaitStacks.add(trace.toString());
                break;
              }
            }
          }
          if (!"objectwait-spanless".equals(thread)) {
            continue;
          }
          spanlessCount++;
          hasNonZeroSpanId |= spanId.getMember(item).longValue() != 0;
          hasNonZeroLocalRootSpanId |= rootSpanId.getMember(item).longValue() != 0;
          hasNonZeroUnblockingSpanId |= unblockingSpanId.getMember(item).longValue() != 0;
          nonZeroBlockerCount += blocker.getMember(item).longValue() == 0 ? 0 : 1;
          hasMissingEventThread |= thread.isEmpty();
        }
      }
    }
  }
}
