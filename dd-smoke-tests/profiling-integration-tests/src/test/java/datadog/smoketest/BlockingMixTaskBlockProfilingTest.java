// Copyright 2026 Datadog, Inc.
package datadog.smoketest;

import static datadog.smoketest.SmokeTestUtils.checkProcessSuccessfullyEnd;
import static datadog.smoketest.TaskBlockProfilingTestSupport.LOCAL_ROOT_SPAN_ID;
import static datadog.smoketest.TaskBlockProfilingTestSupport.SPAN_ID;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.openjdk.jmc.common.item.Attribute.attr;
import static org.openjdk.jmc.common.unit.UnitLookup.TIMESPAN;
import static org.openjdk.jmc.common.unit.UnitLookup.TIMESTAMP;

import datadog.trace.test.util.Flaky;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;

/**
 * End-to-end mixed-blocking smoke / regression / demo test. Combines three roles in one fixture:
 *
 * <ol>
 *   <li><b>Cross-workstream smoke</b>: a single forked JVM under {@code -javaagent:} exercises
 *       {@code Thread.sleep}, {@code LockSupport.park*}, and native {@code synchronized}
 *       contention. Each population's events must be present.
 *   <li><b>NoDoubleBracket</b>: each blocking <em>interval</em> emits exactly one {@code
 *       datadog.TaskBlock} event. Overlapping TaskBlocks on one scenario thread point at a
 *       regression in the Java helper paths vs. the native JVMTI path.
 *   <li><b>BlockingMix demo</b>: the forked app is meant to be copy-pasted as a reproducer when
 *       triaging coverage issues. The runbook below lists JFR inspection commands and the expected
 *       scenario thread-name distribution.
 * </ol>
 *
 * <h3>Demo runbook (manual, off-CI)</h3>
 *
 * <pre>
 *   # 1. Run the forked app standalone to produce a JFR
 *   ./gradlew :dd-smoke-tests:profiling-integration-tests:test \
 *       --tests "*BlockingMixTaskBlockProfilingTest*" \
 *       -Ddatadog.forkedTestRetainDumps=true
 *
 *   # 2. Inspect populations
 *   jfr summary {dumpDir}/*.jfr | grep -E "datadog.TaskBlock|wall=" -A1
 *
 *   # 3. List per-scenario thread counts
 *   jfr print --events "datadog.TaskBlock" {dumpDir}/*.jfr \
 *       | grep -oE "eventThread = \\{[^}]+\\}" | sort | uniq -c
 *
 *   # 4. Expected (steady state):
 *   #     N>=20 blockingmix-sleep
 *   #     N=20  blockingmix-park
 *   #     N=20  blockingmix-sync   (native JVMTI monitor callbacks)
 *
 *   # 5. Native counter snapshot:
 *   jfr print --events "datadog.DatadogProfilerConfig" {dumpDir}/*.jfr
 * </pre>
 */
@DisabledOnJ9
@Flaky(
    "TaskBlock/wall-clock sampler intermittently produces zero events across JDK versions; root cause is tracked separately")
final class BlockingMixTaskBlockProfilingTest {

  private static final IAttribute<IQuantity> START_TIME =
      attr("startTime", "startTime", "startTime", TIMESTAMP);
  private static final IAttribute<IQuantity> DURATION =
      attr("duration", "duration", "duration", TIMESPAN);
  private static final String THREAD_SLEEP = "blockingmix-sleep";
  private static final String THREAD_PARK = "blockingmix-park";
  private static final String THREAD_SYNC = "blockingmix-sync";

  private Path dumpDir;
  private Path logFilePath;

  @BeforeEach
  void setup(TestInfo testInfo) throws IOException {
    logFilePath =
        TaskBlockProfilingTestSupport.buildLogFilePath(
            BlockingMixTaskBlockProfilingTest.class, testInfo, "blockingMix");
    dumpDir = TaskBlockProfilingTestSupport.createDumpDir("dd-profiler-blockingmix-");
  }

  @AfterEach
  void tearDown() throws IOException {
    TaskBlockProfilingTestSupport.deleteRecursively(dumpDir);
  }

  @Test
  @DisplayName("Mixed sleep+park+sync workload emits one TaskBlock per blocking interval")
  void mixedBlockingWorkloadEmitsExpectedPopulations() throws Exception {
    Process targetProcess =
        TaskBlockProfilingTestSupport.createTaskBlockProcessBuilder(
                "smoke-test-blockingmix-taskblock",
                com.datadog.smoketest.profiling.BlockingMixForkedApp.class.getName(),
                dumpDir,
                logFilePath)
            .start();
    checkProcessSuccessfullyEnd(targetProcess, logFilePath);
    assumeDdprofNativeLibraryAvailable();

    JfrStats stats = new JfrStats();
    for (IItemCollection events : TaskBlockProfilingTestSupport.loadDumpedEvents(dumpDir)) {
      stats.add(events);
    }

    // ---- Smoke ----: every population must be present.
    assertTrue(
        stats.countByThread.getOrDefault(THREAD_SLEEP, 0L) > 0,
        "Expected blockingmix.sleep TaskBlock events (thread-sleep call-site module); observed "
            + stats.allCountByThread);
    assertTrue(
        stats.countByThread.getOrDefault(THREAD_PARK, 0L) > 0,
        "Expected blockingmix.park TaskBlock events (existing lock-support module)");
    assertTrue(
        stats.countByThread.getOrDefault(THREAD_SYNC, 0L) > 0,
        "Expected blockingmix.sync TaskBlock events (native JVMTI monitor callbacks)");

    // ---- NoDoubleBracket ----: no two TaskBlock events on the same thread with overlapping
    // intervals for the same operation. This catches shifted start times as well as exact
    // duplicates when Java and native paths both bracket one blocking interval.
    assertFalse(
        stats.hasOverlappingInterval(),
        "Detected overlapping TaskBlock events on one scenario thread — double bracket regression. "
            + "First overlap: "
            + stats.firstOverlapDescription);

    // ---- Span context ----: all TaskBlock events in this workload must be spanless.
    assertFalse(
        stats.hasNonZeroSpanId,
        "TaskBlock events from the mixed workload must all carry zero spanId");
    assertFalse(
        stats.hasNonZeroLocalRootSpanId,
        "TaskBlock events from the mixed workload must all carry zero localRootSpanId");

    // ---- Health ----: no instrumentation classloading or rewrite failures in the forked log.
    assertFalse(
        TaskBlockProfilingTestSupport.logContainsAny(
            logFilePath,
            "NoClassDefFoundError",
            "Failed to handle exception in instrumentation for"),
        "Instrumentation produced classloading / rewrite errors in the forked log");
  }

  private void assumeDdprofNativeLibraryAvailable() throws IOException {
    String log = new String(Files.readAllBytes(logFilePath), StandardCharsets.UTF_8);
    assumeFalse(
        log.contains("libjavaProfiler") && log.contains("not found on classpath"),
        "ddprof native library is not available on this platform");
  }

  private static final class JfrStats {
    final Map<String, Long> countByThread = new HashMap<>();
    final Map<String, Long> allCountByThread = new HashMap<>();
    boolean hasNonZeroSpanId;
    boolean hasNonZeroLocalRootSpanId;
    final Map<String, List<Interval>> intervalsByThread = new HashMap<>();
    String firstOverlapDescription;

    void add(IItemCollection events) {
      IItemCollection taskBlocks = events.apply(ItemFilters.type("datadog.TaskBlock"));
      for (IItemIterable items : taskBlocks) {
        TaskBlockProfilingTestSupport.assertFinalTaskBlockSchema(items);
        IMemberAccessor<IQuantity, IItem> span = SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> root = LOCAL_ROOT_SPAN_ID.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> startTime = START_TIME.getAccessor(items.getType());
        IMemberAccessor<IQuantity, IItem> duration = DURATION.getAccessor(items.getType());
        IMemberAccessor<String, IItem> threadName =
            JdkAttributes.EVENT_THREAD_NAME.getAccessor(items.getType());
        assertNotNull(startTime, "datadog.TaskBlock is missing a startTime accessor");
        assertNotNull(duration, "datadog.TaskBlock is missing a duration accessor");
        assertNotNull(threadName, "datadog.TaskBlock is missing an eventThread accessor");
        for (IItem item : items) {
          String thread = threadName.getMember(item);
          allCountByThread.merge(String.valueOf(thread), 1L, Long::sum);
          if (!THREAD_SLEEP.equals(thread)
              && !THREAD_PARK.equals(thread)
              && !THREAD_SYNC.equals(thread)) {
            continue;
          }
          countByThread.merge(thread, 1L, Long::sum);
          if (span != null) {
            long spanId = span.getMember(item).longValue();
            hasNonZeroSpanId |= spanId != 0L;
          }
          if (root != null) {
            long rootSpanId = root.getMember(item).longValue();
            hasNonZeroLocalRootSpanId |= rootSpanId != 0L;
          }
          long startNanos = startTime.getMember(item).clampedLongValueIn(UnitLookup.EPOCH_NS);
          long durationNanos = duration.getMember(item).clampedLongValueIn(UnitLookup.NANOSECOND);
          intervalsByThread
              .computeIfAbsent(thread, ignored -> new ArrayList<>())
              .add(new Interval(startNanos, saturatedAdd(startNanos, durationNanos)));
        }
      }
    }

    private boolean hasOverlappingInterval() {
      for (Map.Entry<String, List<Interval>> entry : intervalsByThread.entrySet()) {
        List<Interval> intervals = entry.getValue();
        intervals.sort(Comparator.comparingLong(interval -> interval.startNanos));
        for (int i = 1; i < intervals.size(); i++) {
          Interval previous = intervals.get(i - 1);
          Interval current = intervals.get(i);
          if (current.startNanos < previous.endNanos) {
            firstOverlapDescription =
                entry.getKey()
                    + " ["
                    + previous.startNanos
                    + ","
                    + previous.endNanos
                    + ") overlaps ["
                    + current.startNanos
                    + ","
                    + current.endNanos
                    + ")";
            return true;
          }
        }
      }
      return false;
    }

    private static long saturatedAdd(long left, long right) {
      if (right > 0L && left > Long.MAX_VALUE - right) {
        return Long.MAX_VALUE;
      }
      return left + right;
    }
  }

  private static final class Interval {
    private final long startNanos;
    private final long endNanos;

    private Interval(long startNanos, long endNanos) {
      this.startNanos = startNanos;
      this.endNanos = endNanos;
    }
  }
}
