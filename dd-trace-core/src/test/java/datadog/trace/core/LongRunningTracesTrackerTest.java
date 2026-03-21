package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@DisabledIf("isOracleJdk8")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class LongRunningTracesTrackerTest {

  static boolean isOracleJdk8() {
    return JavaVirtualMachine.isOracleJDK8();
  }

  @Mock Config config;
  int maxTrackedTraces = 10;
  @Mock SharedCommunicationObjects sharedCommunicationObjects;
  @Mock DDAgentFeaturesDiscovery features;
  LongRunningTracesTracker tracker;
  @Mock CoreTracer tracer;
  CoreTracer.ConfigSnapshot traceConfig;
  PendingTraceBuffer.DelayingPendingTraceBuffer buffer;
  PendingTrace.Factory factory;
  ControllableTimeSource timeSource = new ControllableTimeSource();

  @BeforeEach
  void setup() {
    traceConfig = Mockito.mock(CoreTracer.ConfigSnapshot.class);
    timeSource.set(0L);
    Mockito.when(features.supportsLongRunning()).thenReturn(true);
    Mockito.when(tracer.captureTraceConfig()).thenReturn(traceConfig);
    Mockito.when(tracer.getTimeWithNanoTicks(Mockito.anyLong()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Mockito.when(traceConfig.getServiceMapping()).thenReturn(java.util.Collections.emptyMap());
    Mockito.when(config.getLongRunningTraceInitialFlushInterval()).thenReturn(10L);
    Mockito.when(config.getLongRunningTraceFlushInterval()).thenReturn(20L);
    Mockito.when(config.isLongRunningTraceEnabled()).thenReturn(true);
    Mockito.when(sharedCommunicationObjects.featuresDiscovery(Mockito.any())).thenReturn(features);
    buffer =
        new PendingTraceBuffer.DelayingPendingTraceBuffer(
            maxTrackedTraces, timeSource, config, sharedCommunicationObjects, HealthMetrics.NO_OP);
    tracker = buffer.runningTracesTracker;
    factory = new PendingTrace.Factory(tracer, buffer, timeSource, false, HealthMetrics.NO_OP);
  }

  @Test
  void nullIsNotAdded() {
    tracker.add(null);
    assertEquals(0, tracker.getTraceArray().size());
  }

  @Test
  void traceWithNoSpanIsNotAdded() {
    tracker.add(factory.create(DDTraceId.ONE));
    assertEquals(0, tracker.getTraceArray().size());
  }

  @Test
  void traceWithoutRightStateAreNotTracked() {
    List<Integer> statesToTest =
        Arrays.asList(
            (int) LongRunningTracesTracker.NOT_TRACKED,
            (int) LongRunningTracesTracker.UNDEFINED,
            (int) LongRunningTracesTracker.TRACKED,
            (int) LongRunningTracesTracker.WRITE_RUNNING_SPANS,
            (int) LongRunningTracesTracker.EXPIRED);

    for (int stateToTest : statesToTest) {
      PendingTrace trace = newTraceToTrack();
      trace.setLongRunningTrackedState(stateToTest);
      tracker.add(trace);
    }
    assertEquals(0, tracker.getTraceArray().size());

    tracker.add(newTraceToTrack());
    assertEquals(1, tracker.getTraceArray().size());
  }

  @Test
  void maxTrackedTracesIsEnforced() {
    for (int i = 0; i < maxTrackedTraces; i++) {
      tracker.add(newTraceToTrack());
    }

    tracker.add(newTraceToTrack());

    assertEquals(maxTrackedTraces, tracker.getTraceArray().size());
    assertEquals(1, tracker.getDropped());
  }

  @Test
  void expiredTraces() {
    PendingTrace trace = newTrackedWithPositivePriority();
    tracker.add(trace);

    tracker.flushAndCompact(tracker.getMaxTrackedDurationMilli() - 1000);

    assertEquals(1, tracker.getTraceArray().size());
    assertEquals(LongRunningTracesTracker.WRITE_RUNNING_SPANS, trace.getLongRunningTrackedState());

    tracker.flushAndCompact(1 + tracker.getMaxTrackedDurationMilli());

    assertEquals(0, tracker.getTraceArray().size());
    assertEquals(LongRunningTracesTracker.EXPIRED, trace.getLongRunningTrackedState());
  }

  @Test
  void agentDisabledFeature() {
    PendingTrace trace = newTraceToTrack();
    tracker.add(trace);

    Mockito.when(features.supportsLongRunning()).thenReturn(false);
    tracker.flushAndCompact(tracker.getFlushPeriodMilli() - 1000);

    assertEquals(0, tracker.getTraceArray().size());
  }

  void flushAt(long timeMilli) {
    timeSource.set(TimeUnit.MILLISECONDS.toNanos(timeMilli));
    tracker.flushAndCompact(timeMilli);
  }

  @Test
  void flushLogicWithInitialFlush() {
    PendingTrace trace = newTrackedWithPositivePriority();
    tracker.add(trace);

    // Before the initial flush
    flushAt(tracker.getInitialFlushPeriodMilli() - 1000);

    Mockito.verify(tracer, Mockito.times(0)).write(Mockito.any());

    // After the initial flush
    flushAt(tracker.getInitialFlushPeriodMilli() + 1000);

    Mockito.verify(tracer, Mockito.times(1)).write(Mockito.any());
    assertEquals(
        TimeUnit.MILLISECONDS.toNanos(tracker.getInitialFlushPeriodMilli() + 1000),
        trace.getLastWriteTime());

    // Before the regular flush
    flushAt(tracker.getInitialFlushPeriodMilli() + tracker.getFlushPeriodMilli() - 1000);

    Mockito.verify(tracer, Mockito.times(1)).write(Mockito.any());
    assertEquals(
        TimeUnit.MILLISECONDS.toNanos(tracker.getInitialFlushPeriodMilli() + 1000),
        trace.getLastWriteTime());

    // After the first regular flush
    flushAt(tracker.getInitialFlushPeriodMilli() + tracker.getFlushPeriodMilli() + 2000);

    Mockito.verify(tracer, Mockito.times(2)).write(Mockito.any());
    assertEquals(
        TimeUnit.MILLISECONDS.toNanos(
            tracker.getInitialFlushPeriodMilli() + tracker.getFlushPeriodMilli() + 2000),
        trace.getLastWriteTime());
  }

  PendingTrace newTrackedWithPositivePriority() {
    PendingTrace trace = newTraceToTrack();
    DDSpan span = trace.getSpans().iterator().next();
    span.context()
        .setSamplingPriority((int) PrioritySampling.SAMPLER_KEEP, SamplingMechanism.DEFAULT);
    return trace;
  }

  PendingTrace newTraceToTrack() {
    PendingTrace trace = factory.create(DDTraceId.ONE);
    PendingTraceBufferTest.newSpanOf(trace, (int) PrioritySampling.UNSET, 0);
    return trace;
  }

  @ParameterizedTest
  @MethodSource("priorityEvaluationArguments")
  void priorityEvaluation(int priority, int trackerExpectedSize, int traceExpectedState) {
    PendingTrace trace = newTraceToTrack();
    DDSpan span =
        (DDSpan) ((java.util.concurrent.ConcurrentLinkedDeque<DDSpan>) trace.getSpans()).peek();
    int mechanism =
        (priority == (int) PrioritySampling.USER_DROP
                || priority == (int) PrioritySampling.USER_KEEP)
            ? SamplingMechanism.MANUAL
            : SamplingMechanism.DEFAULT;
    span.context().setSamplingPriority(priority, mechanism);
    tracker.add(trace);

    tracker.flushAndCompact(tracker.getMaxTrackedDurationMilli() - 1000);

    assertEquals(trackerExpectedSize, tracker.getTraceArray().size());
    assertEquals(traceExpectedState, trace.getLongRunningTrackedState());
  }

  static Stream<Arguments> priorityEvaluationArguments() {
    return Stream.of(
        Arguments.of(
            (int) PrioritySampling.SAMPLER_DROP, 0, (int) LongRunningTracesTracker.NOT_TRACKED),
        Arguments.of(
            (int) PrioritySampling.USER_DROP, 0, (int) LongRunningTracesTracker.NOT_TRACKED),
        Arguments.of(
            (int) PrioritySampling.USER_KEEP,
            1,
            (int) LongRunningTracesTracker.WRITE_RUNNING_SPANS),
        Arguments.of(
            (int) PrioritySampling.SAMPLER_KEEP,
            1,
            (int) LongRunningTracesTracker.WRITE_RUNNING_SPANS));
  }
}
