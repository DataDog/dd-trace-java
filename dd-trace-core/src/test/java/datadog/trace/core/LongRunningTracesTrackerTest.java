package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.junit.utils.config.WithConfigExtension;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

public class LongRunningTracesTrackerTest extends DDJavaSpecification {

  private static final long INITIAL_FLUSH_PERIOD_MILLI = TimeUnit.SECONDS.toMillis(10);
  private static final long FLUSH_PERIOD_MILLI = TimeUnit.SECONDS.toMillis(20);
  private static final long MAX_TRACKED_DURATION_MILLI = TimeUnit.HOURS.toMillis(12);
  private static final int MAX_TRACKED_TRACES = 10;

  private CoreTracer tracer;
  private CoreTracer.ConfigSnapshot traceConfig;
  private DDAgentFeaturesDiscovery features;
  private SharedCommunicationObjects sharedCommunicationObjects;
  private ControllableTimeSource timeSource;
  private PendingTraceBuffer.DelayingPendingTraceBuffer buffer;
  private LongRunningTracesTracker tracker;
  private PendingTrace.Factory factory;

  @BeforeAll
  static void checkJvm() {
    Assumptions.assumeFalse(
        JavaVirtualMachine.isOracleJDK8(),
        "Oracle JDK 1.8 did not merge the fix in JDK-8058322, leading to the JVM failing to"
            + " correctly extract method parameters without args, when the code is compiled on a"
            + " later JDK (targeting 8). This can manifest when creating mocks.");
  }

  @BeforeEach
  void setup() {
    WithConfigExtension.injectSysConfig("trace.experimental.long-running.enabled", "true");
    WithConfigExtension.injectSysConfig(
        "trace.experimental.long-running.initial.flush.interval", "10");
    WithConfigExtension.injectSysConfig("trace.experimental.long-running.flush.interval", "20");

    tracer = mock(CoreTracer.class);
    traceConfig = mock(CoreTracer.ConfigSnapshot.class);
    features = mock(DDAgentFeaturesDiscovery.class);
    sharedCommunicationObjects = mock(SharedCommunicationObjects.class);
    timeSource = new ControllableTimeSource();
    timeSource.set(0L);

    when(features.supportsLongRunning()).thenReturn(true);
    when(tracer.captureTraceConfig()).thenReturn(traceConfig);
    when(tracer.getTimeWithNanoTicks(anyLong())).thenAnswer(inv -> inv.<Long>getArgument(0));
    when(traceConfig.getServiceMapping()).thenReturn(Collections.emptyMap());
    when(sharedCommunicationObjects.featuresDiscovery(any())).thenReturn(features);

    buffer =
        new PendingTraceBuffer.DelayingPendingTraceBuffer(
            MAX_TRACKED_TRACES,
            timeSource,
            Config.get(),
            sharedCommunicationObjects,
            HealthMetrics.NO_OP);
    tracker = buffer.getRunningTracesTracker();
    factory = new PendingTrace.Factory(tracer, buffer, timeSource, false, HealthMetrics.NO_OP);
  }

  @Test
  void nullIsNotAdded() {
    tracker.add(null);
    assertEquals(0, tracker.trackedCount());
  }

  @Test
  void traceWithNoSpanIsNotAdded() {
    tracker.add(factory.create(DDTraceId.ONE));
    assertEquals(0, tracker.trackedCount());
  }

  @Test
  void traceWithoutRightStateAreNotTracked() {
    List<Integer> statesToTest =
        Arrays.asList(
            LongRunningTracesTracker.NOT_TRACKED,
            LongRunningTracesTracker.UNDEFINED,
            LongRunningTracesTracker.TRACKED,
            LongRunningTracesTracker.WRITE_RUNNING_SPANS,
            LongRunningTracesTracker.EXPIRED);
    for (int stateToTest : statesToTest) {
      PendingTrace trace = newTraceToTrack();
      trace.setLongRunningTrackedState(stateToTest);
      tracker.add(trace);
    }
    assertEquals(0, tracker.trackedCount());

    tracker.add(newTraceToTrack());
    assertEquals(1, tracker.trackedCount());
  }

  @Test
  void maxTrackedTracesIsEnforced() {
    for (int i = 0; i < MAX_TRACKED_TRACES; i++) {
      tracker.add(newTraceToTrack());
    }
    tracker.add(newTraceToTrack());
    assertEquals(MAX_TRACKED_TRACES, tracker.trackedCount());
    assertEquals(1, tracker.getDropped());
  }

  @Test
  void expiredTraces() {
    PendingTrace trace = newTraceToTrack();
    tracker.add(trace);

    tracker.flushAndCompact(MAX_TRACKED_DURATION_MILLI - 1000);
    assertEquals(1, tracker.trackedCount());
    assertEquals(LongRunningTracesTracker.WRITE_RUNNING_SPANS, trace.getLongRunningTrackedState());

    tracker.flushAndCompact(1 + MAX_TRACKED_DURATION_MILLI);
    assertEquals(0, tracker.trackedCount());
    assertEquals(LongRunningTracesTracker.EXPIRED, trace.getLongRunningTrackedState());
  }

  @Test
  void agentDisabledFeature() {
    PendingTrace trace = newTraceToTrack();
    tracker.add(trace);

    when(features.supportsLongRunning()).thenReturn(false);
    tracker.flushAndCompact(FLUSH_PERIOD_MILLI - 1000);
    assertEquals(0, tracker.trackedCount());
  }

  @Test
  void flushLogicWithInitialFlush() {
    PendingTrace trace = newTraceToTrack();
    tracker.add(trace);

    // Before the initial flush
    flushAt(INITIAL_FLUSH_PERIOD_MILLI - 1000);
    verify(tracer, never()).write(any());
    clearInvocations(tracer);

    // After the initial flush
    flushAt(INITIAL_FLUSH_PERIOD_MILLI + 1000);
    verify(tracer, times(1)).write(any());
    assertEquals(
        TimeUnit.MILLISECONDS.toNanos(INITIAL_FLUSH_PERIOD_MILLI + 1000), trace.getLastWriteTime());
    clearInvocations(tracer);

    // Before the regular flush
    flushAt(INITIAL_FLUSH_PERIOD_MILLI + FLUSH_PERIOD_MILLI - 1000);
    verify(tracer, never()).write(any());
    assertEquals(
        TimeUnit.MILLISECONDS.toNanos(INITIAL_FLUSH_PERIOD_MILLI + 1000), trace.getLastWriteTime());
    clearInvocations(tracer);

    // After the first regular flush
    flushAt(INITIAL_FLUSH_PERIOD_MILLI + FLUSH_PERIOD_MILLI + 2000);
    verify(tracer, times(1)).write(any());
    assertEquals(
        TimeUnit.MILLISECONDS.toNanos(INITIAL_FLUSH_PERIOD_MILLI + FLUSH_PERIOD_MILLI + 2000),
        trace.getLastWriteTime());
  }

  @TableTest({
    "scenario     | priority | trackerExpectedSize | traceExpectedState",
    "sampler drop | 0        | 0                   | -1                ",
    "user drop    | -1       | 0                   | -1                ",
    "user keep    | 2        | 1                   | 3                 ",
    "sampler keep | 1        | 1                   | 3                 "
  })
  void priorityEvaluation(int priority, int trackerExpectedSize, int traceExpectedState) {
    PendingTrace trace = factory.create(DDTraceId.ONE);
    newSpanOf(trace, priority, 0);
    tracker.add(trace);

    tracker.flushAndCompact(MAX_TRACKED_DURATION_MILLI - 1000);

    assertEquals(trackerExpectedSize, tracker.trackedCount());
    assertEquals(traceExpectedState, trace.getLongRunningTrackedState());
  }

  private void flushAt(long timeMilli) {
    timeSource.set(TimeUnit.MILLISECONDS.toNanos(timeMilli));
    tracker.flushAndCompact(timeMilli);
  }

  private PendingTrace newTraceToTrack() {
    PendingTrace trace = factory.create(DDTraceId.ONE);
    newSpanOf(trace, PrioritySampling.SAMPLER_KEEP, 0);
    return trace;
  }

  private static DDSpan newSpanOf(PendingTrace trace, int samplingPriority, long timestampMicro) {
    DDSpanContext context =
        new DDSpanContext(
            DDTraceId.ONE,
            1,
            DDSpanId.ZERO,
            null,
            "fakeService",
            "fakeOperation",
            "fakeResource",
            samplingPriority,
            null,
            Collections.emptyMap(),
            false,
            "fakeType",
            0,
            trace,
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            PropagationTags.factory().empty());
    return DDSpan.create("test", timestampMicro, context, null);
  }
}
