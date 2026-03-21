package datadog.trace.common.writer;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.common.writer.ddagent.Prioritization.ENSURE_TRACE;
import static datadog.trace.common.writer.ddagent.Prioritization.FAST_LANE;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.DROPPED_BUFFER_OVERFLOW;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.DROPPED_BY_POLICY;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.ENQUEUED_FOR_SERIALIZATION;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.ENQUEUED_FOR_SINGLE_SPAN_SAMPLING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.common.writer.ddagent.FlushEvent;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.common.writer.ddagent.PrioritizationStrategy;
import datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult;
import datadog.trace.core.DDSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

class PrioritizationTest {

  @SuppressWarnings("unchecked")
  private static final List<DDSpan> EMPTY_TRACE = (List<DDSpan>) (List<?>) new ArrayList<>();

  @TableTest({
    "scenario                        | primaryFull | priority     | primaryOffers | secondaryOffers",
    "unset primary full              | true        | UNSET        | 2             | 0              ",
    "sampler drop primary full       | true        | SAMPLER_DROP | 0             | 1              ",
    "sampler keep primary full       | true        | SAMPLER_KEEP | 2             | 0              ",
    "sampler drop primary full 2     | true        | SAMPLER_DROP | 0             | 1              ",
    "user keep primary full          | true        | USER_KEEP    | 2             | 0              ",
    "unset primary not full          | false       | UNSET        | 1             | 0              ",
    "sampler drop primary not full   | false       | SAMPLER_DROP | 0             | 1              ",
    "sampler keep primary not full   | false       | SAMPLER_KEEP | 1             | 0              ",
    "sampler drop primary not full 2 | false       | SAMPLER_DROP | 0             | 1              ",
    "user keep primary not full      | false       | USER_KEEP    | 1             | 0              "
  })
  @ParameterizedTest(name = "[{index}] {0}")
  @SuppressWarnings("unchecked")
  void ensureTraceStrategyTriesToSendKeptAndUnsetPriorityTracesToPrimaryQueue(
      boolean primaryFull, String priorityStr, int primaryOffers, int secondaryOffers) {
    int priority = parsePriority(priorityStr);
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> secondary = mock(Queue.class);
    PrioritizationStrategy blocking = ENSURE_TRACE.create(primary, secondary, null, () -> false);

    // Set up primary queue mock - first offer returns !primaryFull, then true
    if (primaryFull && primaryOffers == 2) {
      when(primary.offer(EMPTY_TRACE)).thenReturn(false).thenReturn(true);
    } else if (!primaryFull && primaryOffers == 1) {
      when(primary.offer(EMPTY_TRACE)).thenReturn(true);
    }
    if (secondaryOffers > 0) {
      when(secondary.offer(EMPTY_TRACE)).thenReturn(true);
    }

    PublishResult publishResult = blocking.publish(mock(DDSpan.class), priority, EMPTY_TRACE);

    assertEquals(ENQUEUED_FOR_SERIALIZATION, publishResult);
    verify(primary, times(primaryOffers)).offer(EMPTY_TRACE);
    verify(secondary, times(secondaryOffers)).offer(EMPTY_TRACE);
    verifyNoMoreInteractions(primary, secondary);
  }

  @TableTest({
    "scenario       | priority     | primaryOffers | secondaryOffers",
    "unset          | UNSET        | 1             | 0              ",
    "sampler drop   | SAMPLER_DROP | 0             | 1              ",
    "sampler keep   | SAMPLER_KEEP | 1             | 0              ",
    "sampler drop 2 | SAMPLER_DROP | 0             | 1              ",
    "user keep      | USER_KEEP    | 1             | 0              "
  })
  @ParameterizedTest(name = "[{index}] {0}")
  @SuppressWarnings("unchecked")
  void fastLaneStrategySendsKeptAndUnsetPriorityTracesToPrimaryQueue(
      String priorityStr, int primaryOffers, int secondaryOffers) {
    int priority = parsePriority(priorityStr);
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> secondary = mock(Queue.class);
    PrioritizationStrategy fastLane = FAST_LANE.create(primary, secondary, null, () -> false);

    PublishResult publishResult = fastLane.publish(mock(DDSpan.class), priority, EMPTY_TRACE);

    assertEquals(DROPPED_BUFFER_OVERFLOW, publishResult);
    verify(primary, times(primaryOffers)).offer(EMPTY_TRACE);
    verify(secondary, times(secondaryOffers)).offer(EMPTY_TRACE);
    verifyNoMoreInteractions(primary, secondary);
  }

  @TableTest({
    "scenario       | priority     | primaryOffers | expectedResult            ",
    "unset          | UNSET        | 1             | ENQUEUED_FOR_SERIALIZATION",
    "sampler drop   | SAMPLER_DROP | 0             | DROPPED_BY_POLICY         ",
    "sampler keep   | SAMPLER_KEEP | 1             | ENQUEUED_FOR_SERIALIZATION",
    "sampler drop 2 | SAMPLER_DROP | 0             | DROPPED_BY_POLICY         ",
    "user keep      | USER_KEEP    | 1             | ENQUEUED_FOR_SERIALIZATION"
  })
  @ParameterizedTest(name = "[{index}] {0}")
  @SuppressWarnings("unchecked")
  void fastLaneWithActiveDroppingPolicySendsKeptAndUnsetPriorityTracesToPrimaryQueue(
      String priorityStr, int primaryOffers, String expectedResultStr) {
    int priority = parsePriority(priorityStr);
    PublishResult expectedResult = parsePublishResult(expectedResultStr);
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> secondary = mock(Queue.class);
    PrioritizationStrategy drop = FAST_LANE.create(primary, secondary, null, () -> true);

    if (primaryOffers > 0) {
      when(primary.offer(EMPTY_TRACE)).thenReturn(true);
    }

    PublishResult publishResult = drop.publish(mock(DDSpan.class), priority, EMPTY_TRACE);

    assertEquals(expectedResult, publishResult);
    verify(primary, times(primaryOffers)).offer(EMPTY_TRACE);
    verify(secondary, never()).offer(EMPTY_TRACE);
    verifyNoMoreInteractions(primary, secondary);
  }

  @TableTest({
    "scenario     | strategy    ",
    "FAST_LANE    | FAST_LANE   ",
    "ENSURE_TRACE | ENSURE_TRACE"
  })
  @ParameterizedTest(name = "[{index}] {0} strategy flushes primary queue")
  @SuppressWarnings("unchecked")
  void strategyFlushesPrimaryQueue(String strategyStr) throws Exception {
    Prioritization strategy = Prioritization.valueOf(strategyStr.trim());
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> secondary = mock(Queue.class);
    PrioritizationStrategy fastLane = strategy.create(primary, secondary, null, () -> false);
    when(primary.offer(org.mockito.ArgumentMatchers.any(FlushEvent.class))).thenReturn(true);

    fastLane.flush(100, TimeUnit.MILLISECONDS);

    verify(primary, times(1)).offer(org.mockito.ArgumentMatchers.any(FlushEvent.class));
    verify(secondary, never()).offer(org.mockito.ArgumentMatchers.any());
    verifyNoMoreInteractions(primary, secondary);
  }

  @TableTest({
    "scenario         | strategy  | forceKeep | expectedResult            ",
    "force keep true  | FAST_LANE | true      | ENQUEUED_FOR_SERIALIZATION",
    "force keep false | FAST_LANE | false     | DROPPED_BY_POLICY         "
  })
  @ParameterizedTest(name = "[{index}] {0}")
  @SuppressWarnings("unchecked")
  void dropStrategyRespectsForceKeep(
      String strategyStr, boolean forceKeep, String expectedResultStr) {
    Prioritization strategy = Prioritization.valueOf(strategyStr.trim());
    PublishResult expectedResult = parsePublishResult(expectedResultStr);
    Queue<Object> primary = mock(Queue.class);
    PrioritizationStrategy drop = strategy.create(primary, null, null, () -> true);
    DDSpan root = mock(DDSpan.class);
    List<DDSpan> trace = new ArrayList<>();
    trace.add(root);

    when(root.isForceKeep()).thenReturn(forceKeep);
    if (forceKeep) {
      when(primary.offer(trace)).thenReturn(true);
    }

    PublishResult publishResult = drop.publish(root, SAMPLER_DROP, trace);

    assertEquals(expectedResult, publishResult);
    verify(root, times(1)).isForceKeep();
    verify(primary, times(forceKeep ? 1 : 0)).offer(trace);
    verifyNoMoreInteractions(root, primary);
  }

  @TableTest({
    "scenario                                   | primaryFull | priority     | primaryOffers | singleSpanOffers | singleSpanFull | expectedResult                   ",
    "unset primary full ss not full             | true        | UNSET        | 2             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "sampler drop primary full ss not full      | true        | SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "sampler keep primary full ss not full      | true        | SAMPLER_KEEP | 2             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "sampler drop2 primary full ss not full     | true        | SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "user keep primary full ss not full         | true        | USER_KEEP    | 2             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "unset primary not full ss not full         | false       | UNSET        | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "sampler drop primary not full ss not full  | false       | SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "sampler keep primary not full ss not full  | false       | SAMPLER_KEEP | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "sampler drop2 primary not full ss not full | false       | SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "user keep primary not full ss not full     | false       | USER_KEEP    | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "unset primary full ss full                 | true        | UNSET        | 2             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "sampler drop primary full ss full          | true        | SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW          ",
    "sampler keep primary full ss full          | true        | SAMPLER_KEEP | 2             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "sampler drop2 primary full ss full         | true        | SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW          ",
    "user keep primary full ss full             | true        | USER_KEEP    | 2             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "unset primary not full ss full             | false       | UNSET        | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "sampler drop primary not full ss full      | false       | SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW          ",
    "sampler keep primary not full ss full      | false       | SAMPLER_KEEP | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "sampler drop2 primary not full ss full     | false       | SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW          ",
    "user keep primary not full ss full         | false       | USER_KEEP    | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       "
  })
  @ParameterizedTest(name = "[{index}] {0}")
  @SuppressWarnings("unchecked")
  void ensureTraceStrategyWithSpanSamplingQueue(
      boolean primaryFull,
      String priorityStr,
      int primaryOffers,
      int singleSpanOffers,
      boolean singleSpanFull,
      String expectedResultStr) {
    int priority = parsePriority(priorityStr);
    PublishResult expectedResult = parsePublishResult(expectedResultStr);
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> secondary = mock(Queue.class);
    Queue<Object> spanSampling = mock(Queue.class);
    PrioritizationStrategy blocking =
        ENSURE_TRACE.create(primary, secondary, spanSampling, () -> false);

    if (primaryFull && primaryOffers == 2) {
      when(primary.offer(EMPTY_TRACE)).thenReturn(false).thenReturn(true);
    } else if (!primaryFull && primaryOffers == 1) {
      when(primary.offer(EMPTY_TRACE)).thenReturn(true);
    }
    if (singleSpanOffers > 0) {
      when(spanSampling.offer(EMPTY_TRACE)).thenReturn(!singleSpanFull);
    }

    PublishResult publishResult = blocking.publish(mock(DDSpan.class), priority, EMPTY_TRACE);

    assertEquals(expectedResult, publishResult);
    verify(primary, times(primaryOffers)).offer(EMPTY_TRACE);
    verify(secondary, never()).offer(EMPTY_TRACE);
    verify(spanSampling, times(singleSpanOffers)).offer(EMPTY_TRACE);
    verifyNoMoreInteractions(primary, secondary, spanSampling);
  }

  @TableTest({
    "scenario                  | priority     | primaryOffers | singleSpanOffers | singleSpanFull | expectedResult                   ",
    "unset ss not full         | UNSET        | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "sampler drop ss not full  | SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "sampler keep ss not full  | SAMPLER_KEEP | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "sampler drop2 ss not full | SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "user keep ss not full     | USER_KEEP    | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "unset ss full             | UNSET        | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "sampler drop ss full      | SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW          ",
    "sampler keep ss full      | SAMPLER_KEEP | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "sampler drop2 ss full     | SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW          ",
    "user keep ss full         | USER_KEEP    | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       "
  })
  @ParameterizedTest(name = "[{index}] {0}")
  @SuppressWarnings("unchecked")
  void fastLaneStrategyWithSpanSamplingQueue(
      String priorityStr,
      int primaryOffers,
      int singleSpanOffers,
      boolean singleSpanFull,
      String expectedResultStr) {
    int priority = parsePriority(priorityStr);
    PublishResult expectedResult = parsePublishResult(expectedResultStr);
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> secondary = mock(Queue.class);
    Queue<Object> spanSampling = mock(Queue.class);
    PrioritizationStrategy fastLane =
        FAST_LANE.create(primary, secondary, spanSampling, () -> false);

    if (primaryOffers > 0) {
      when(primary.offer(EMPTY_TRACE)).thenReturn(true);
    }
    if (singleSpanOffers > 0) {
      when(spanSampling.offer(EMPTY_TRACE)).thenReturn(!singleSpanFull);
    }

    PublishResult publishResult = fastLane.publish(mock(DDSpan.class), priority, EMPTY_TRACE);

    assertEquals(expectedResult, publishResult);
    verify(primary, times(primaryOffers)).offer(EMPTY_TRACE);
    verify(secondary, never()).offer(EMPTY_TRACE);
    verify(spanSampling, times(singleSpanOffers)).offer(EMPTY_TRACE);
    verifyNoMoreInteractions(primary, secondary, spanSampling);
  }

  @TableTest({
    "scenario      | priority     | primaryOffers | singleSpanOffers | expectedResult                   ",
    "unset         | UNSET        | 1             | 0                | ENQUEUED_FOR_SERIALIZATION       ",
    "sampler drop  | SAMPLER_DROP | 0             | 1                | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "sampler keep  | SAMPLER_KEEP | 1             | 0                | ENQUEUED_FOR_SERIALIZATION       ",
    "sampler drop2 | SAMPLER_DROP | 0             | 1                | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "user keep     | USER_KEEP    | 1             | 0                | ENQUEUED_FOR_SERIALIZATION       "
  })
  @ParameterizedTest(name = "[{index}] {0}")
  @SuppressWarnings("unchecked")
  void fastLaneWithActiveDroppingPolicySendsToSingleSpanSampling(
      String priorityStr, int primaryOffers, int singleSpanOffers, String expectedResultStr) {
    int priority = parsePriority(priorityStr);
    PublishResult expectedResult = parsePublishResult(expectedResultStr);
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> secondary = mock(Queue.class);
    Queue<Object> spanSampling = mock(Queue.class);
    PrioritizationStrategy drop = FAST_LANE.create(primary, secondary, spanSampling, () -> true);

    if (primaryOffers > 0) {
      when(primary.offer(EMPTY_TRACE)).thenReturn(true);
    }
    if (singleSpanOffers > 0) {
      when(spanSampling.offer(EMPTY_TRACE)).thenReturn(true);
    }

    PublishResult publishResult = drop.publish(mock(DDSpan.class), priority, EMPTY_TRACE);

    assertEquals(expectedResult, publishResult);
    verify(primary, times(primaryOffers)).offer(EMPTY_TRACE);
    verify(secondary, never()).offer(EMPTY_TRACE);
    verify(spanSampling, times(singleSpanOffers)).offer(EMPTY_TRACE);
    verifyNoMoreInteractions(primary, secondary, spanSampling);
  }

  @TableTest({
    "scenario                     | strategy  | forceKeep | singleSpanFull | expectedResult                   ",
    "force keep true ss full      | FAST_LANE | true      | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "force keep false ss full     | FAST_LANE | false     | true           | DROPPED_BUFFER_OVERFLOW          ",
    "force keep true ss not full  | FAST_LANE | true      | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "force keep false ss not full | FAST_LANE | false     | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING"
  })
  @ParameterizedTest(name = "[{index}] {0}")
  @SuppressWarnings("unchecked")
  void spanSamplingDropStrategyRespectsForceKeep(
      String strategyStr, boolean forceKeep, boolean singleSpanFull, String expectedResultStr) {
    Prioritization strategy = Prioritization.valueOf(strategyStr.trim());
    PublishResult expectedResult = parsePublishResult(expectedResultStr);
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> spanSampling = mock(Queue.class);
    PrioritizationStrategy drop = strategy.create(primary, null, spanSampling, () -> true);
    DDSpan root = mock(DDSpan.class);
    List<DDSpan> trace = new ArrayList<>();
    trace.add(root);

    when(root.isForceKeep()).thenReturn(forceKeep);
    if (forceKeep) {
      when(primary.offer(trace)).thenReturn(true);
    } else {
      when(spanSampling.offer(trace)).thenReturn(!singleSpanFull);
    }

    PublishResult publishResult = drop.publish(root, SAMPLER_DROP, trace);

    assertEquals(expectedResult, publishResult);
    verify(root, times(1)).isForceKeep();
    verify(primary, times(forceKeep ? 1 : 0)).offer(trace);
    verify(spanSampling, times(forceKeep ? 0 : 1)).offer(trace);
    verifyNoMoreInteractions(root, primary, spanSampling);
  }

  private static int parsePriority(String priorityStr) {
    switch (priorityStr.trim()) {
      case "UNSET":
        return UNSET;
      case "SAMPLER_DROP":
        return SAMPLER_DROP;
      case "SAMPLER_KEEP":
        return SAMPLER_KEEP;
      case "USER_KEEP":
        return USER_KEEP;
      default:
        throw new IllegalArgumentException("Unknown priority: " + priorityStr);
    }
  }

  private static PublishResult parsePublishResult(String resultStr) {
    switch (resultStr.trim()) {
      case "ENQUEUED_FOR_SERIALIZATION":
        return ENQUEUED_FOR_SERIALIZATION;
      case "DROPPED_BUFFER_OVERFLOW":
        return DROPPED_BUFFER_OVERFLOW;
      case "DROPPED_BY_POLICY":
        return DROPPED_BY_POLICY;
      case "ENQUEUED_FOR_SINGLE_SPAN_SAMPLING":
        return ENQUEUED_FOR_SINGLE_SPAN_SAMPLING;
      default:
        throw new IllegalArgumentException("Unknown result: " + resultStr);
    }
  }
}
