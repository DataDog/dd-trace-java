package datadog.trace.common.writer;

import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.DROPPED_BUFFER_OVERFLOW;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.ENQUEUED_FOR_SERIALIZATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.ddagent.FlushEvent;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.common.writer.ddagent.PrioritizationStrategy;
import datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult;
import datadog.trace.core.DDSpan;
import datadog.trace.junit.utils.tabletest.PrioritySamplingConverter;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class PrioritizationTest extends DDJavaSpecification {

  @SuppressWarnings("unchecked")
  @TableTest({
    "scenario           | primaryFull | priority                      | primaryOffers | secondaryOffers",
    "unset full         | true        | PrioritySampling.UNSET        | 2             | 0              ",
    "drop full          | true        | PrioritySampling.SAMPLER_DROP | 0             | 1              ",
    "keep full          | true        | PrioritySampling.SAMPLER_KEEP | 2             | 0              ",
    "drop full 2        | true        | PrioritySampling.SAMPLER_DROP | 0             | 1              ",
    "user keep full     | true        | PrioritySampling.USER_KEEP    | 2             | 0              ",
    "unset not full     | false       | PrioritySampling.UNSET        | 1             | 0              ",
    "drop not full      | false       | PrioritySampling.SAMPLER_DROP | 0             | 1              ",
    "keep not full      | false       | PrioritySampling.SAMPLER_KEEP | 1             | 0              ",
    "drop not full 2    | false       | PrioritySampling.SAMPLER_DROP | 0             | 1              ",
    "user keep not full | false       | PrioritySampling.USER_KEEP    | 1             | 0              "
  })
  void testEnsureTraceStrategyTriesToSendKeptAndUnsetPriorityTracesToPrimaryQueue(
      boolean primaryFull,
      @ConvertWith(PrioritySamplingConverter.class) int priority,
      int primaryOffers,
      int secondaryOffers) {
    List<DDSpan> trace = Collections.emptyList();
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> secondary = mock(Queue.class);
    PrioritizationStrategy blocking =
        Prioritization.ENSURE_TRACE.create(primary, secondary, null, () -> false);
    // stub: first offer returns !primaryFull, second offer returns true
    when(primary.offer(trace)).thenReturn(!primaryFull, true);
    when(secondary.offer(trace)).thenReturn(true);

    PublishResult publishResult = blocking.publish(mock(DDSpan.class), priority, trace);

    assertEquals(ENQUEUED_FOR_SERIALIZATION, publishResult);
    verify(primary, times(primaryOffers)).offer(trace);
    verify(secondary, times(secondaryOffers)).offer(trace);
  }

  @SuppressWarnings("unchecked")
  @TableTest({
    "scenario  | priority                      | primaryOffers | secondaryOffers",
    "unset     | PrioritySampling.UNSET        | 1             | 0              ",
    "drop      | PrioritySampling.SAMPLER_DROP | 0             | 1              ",
    "keep      | PrioritySampling.SAMPLER_KEEP | 1             | 0              ",
    "drop 2    | PrioritySampling.SAMPLER_DROP | 0             | 1              ",
    "user keep | PrioritySampling.USER_KEEP    | 1             | 0              "
  })
  void testFastLaneStrategySendsKeptAndUnsetPriorityTracesToPrimaryQueue(
      @ConvertWith(PrioritySamplingConverter.class) int priority,
      int primaryOffers,
      int secondaryOffers) {
    List<DDSpan> trace = Collections.emptyList();
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> secondary = mock(Queue.class);
    PrioritizationStrategy fastLane =
        Prioritization.FAST_LANE.create(primary, secondary, null, () -> false);

    PublishResult publishResult = fastLane.publish(mock(DDSpan.class), priority, trace);

    assertEquals(DROPPED_BUFFER_OVERFLOW, publishResult);
    verify(primary, times(primaryOffers)).offer(trace);
    verify(secondary, times(secondaryOffers)).offer(trace);
  }

  @SuppressWarnings("unchecked")
  @TableTest({
    "scenario  | priority                      | primaryOffers | expectedResult            ",
    "unset     | PrioritySampling.UNSET        | 1             | ENQUEUED_FOR_SERIALIZATION",
    "drop      | PrioritySampling.SAMPLER_DROP | 0             | DROPPED_BY_POLICY         ",
    "keep      | PrioritySampling.SAMPLER_KEEP | 1             | ENQUEUED_FOR_SERIALIZATION",
    "drop 2    | PrioritySampling.SAMPLER_DROP | 0             | DROPPED_BY_POLICY         ",
    "user keep | PrioritySampling.USER_KEEP    | 1             | ENQUEUED_FOR_SERIALIZATION"
  })
  void testFastLaneWithActiveDroppingPolicySendsKeptAndUnsetTracesToPrimaryQueue(
      @ConvertWith(PrioritySamplingConverter.class) int priority,
      int primaryOffers,
      PublishResult expectedResult) {
    List<DDSpan> trace = Collections.emptyList();
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> secondary = mock(Queue.class);
    PrioritizationStrategy drop =
        Prioritization.FAST_LANE.create(primary, secondary, null, () -> true);
    when(primary.offer(trace)).thenReturn(true);

    PublishResult publishResult = drop.publish(mock(DDSpan.class), priority, trace);

    assertEquals(expectedResult, publishResult);
    verify(primary, times(primaryOffers)).offer(trace);
    verify(secondary, never()).offer(trace);
  }

  @SuppressWarnings("unchecked")
  @TableTest({
    "scenario     | strategy    ",
    "fast lane    | FAST_LANE   ",
    "ensure trace | ENSURE_TRACE"
  })
  void testStrategyFlushesPrimaryQueue(Prioritization strategy) {
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> secondary = mock(Queue.class);
    PrioritizationStrategy prioritizationStrategy =
        strategy.create(primary, secondary, null, () -> false);
    when(primary.offer(any())).thenReturn(true);

    prioritizationStrategy.flush(100, TimeUnit.MILLISECONDS);

    verify(primary).offer(any(FlushEvent.class));
    verify(secondary, never()).offer(any());
  }

  @SuppressWarnings("unchecked")
  @TableTest({
    "scenario                   | strategy  | forceKeep | expectedResult            ",
    "force keep true fast lane  | FAST_LANE | true      | ENQUEUED_FOR_SERIALIZATION",
    "force keep false fast lane | FAST_LANE | false     | DROPPED_BY_POLICY         "
  })
  void testDropStrategyRespectsForceKeep(
      Prioritization strategy, boolean forceKeep, PublishResult expectedResult) {
    Queue<Object> primary = mock(Queue.class);
    PrioritizationStrategy drop = strategy.create(primary, null, null, () -> true);
    DDSpan root = mock(DDSpan.class);
    List<DDSpan> trace = Collections.singletonList(root);
    when(root.isForceKeep()).thenReturn(forceKeep);
    when(primary.offer(trace)).thenReturn(true);

    PublishResult publishResult = drop.publish(root, PrioritySampling.SAMPLER_DROP, trace);

    assertEquals(expectedResult, publishResult);
    verify(root).isForceKeep();
    verify(primary, times(forceKeep ? 1 : 0)).offer(trace);
    verifyNoMoreInteractions(root, primary);
  }

  @SuppressWarnings("unchecked")
  @TableTest({
    "scenario                 | primaryFull | priority                      | primaryOffers | singleSpanOffers | singleSpanFull | expectedResult                   ",
    "unset full ss-not-full   | true        | PrioritySampling.UNSET        | 2             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "drop full ss-not-full    | true        | PrioritySampling.SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "keep full ss-not-full    | true        | PrioritySampling.SAMPLER_KEEP | 2             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "drop full 2 ss-not-full  | true        | PrioritySampling.SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "ukeep full ss-not-full   | true        | PrioritySampling.USER_KEEP    | 2             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "unset nfull ss-not-full  | false       | PrioritySampling.UNSET        | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "drop nfull ss-not-full   | false       | PrioritySampling.SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "keep nfull ss-not-full   | false       | PrioritySampling.SAMPLER_KEEP | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "drop nfull 2 ss-not-full | false       | PrioritySampling.SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "ukeep nfull ss-not-full  | false       | PrioritySampling.USER_KEEP    | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "unset full ss-full       | true        | PrioritySampling.UNSET        | 2             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "drop full ss-full        | true        | PrioritySampling.SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW          ",
    "keep full ss-full        | true        | PrioritySampling.SAMPLER_KEEP | 2             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "drop full 2 ss-full      | true        | PrioritySampling.SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW          ",
    "ukeep full ss-full       | true        | PrioritySampling.USER_KEEP    | 2             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "unset nfull ss-full      | false       | PrioritySampling.UNSET        | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "drop nfull ss-full       | false       | PrioritySampling.SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW          ",
    "keep nfull ss-full       | false       | PrioritySampling.SAMPLER_KEEP | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "drop nfull 2 ss-full     | false       | PrioritySampling.SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW          ",
    "ukeep nfull ss-full      | false       | PrioritySampling.USER_KEEP    | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       "
  })
  void testEnsureTraceStrategyWithSpanSamplingQueue(
      boolean primaryFull,
      @ConvertWith(PrioritySamplingConverter.class) int priority,
      int primaryOffers,
      int singleSpanOffers,
      boolean singleSpanFull,
      PublishResult expectedResult) {
    List<DDSpan> trace = Collections.emptyList();
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> secondary = mock(Queue.class);
    Queue<Object> spanSampling = mock(Queue.class);
    PrioritizationStrategy blocking =
        Prioritization.ENSURE_TRACE.create(primary, secondary, spanSampling, () -> false);
    when(primary.offer(trace)).thenReturn(!primaryFull, true);
    when(spanSampling.offer(trace)).thenReturn(!singleSpanFull);

    PublishResult publishResult = blocking.publish(mock(DDSpan.class), priority, trace);

    assertEquals(expectedResult, publishResult);
    verify(primary, times(primaryOffers)).offer(trace);
    verify(secondary, never()).offer(trace); // expect no traces sent to the secondary queue
    verify(spanSampling, times(singleSpanOffers)).offer(trace);
  }

  @SuppressWarnings("unchecked")
  @TableTest({
    "scenario              | priority                      | primaryOffers | singleSpanOffers | singleSpanFull | expectedResult                   ",
    "unset ss-not-full     | PrioritySampling.UNSET        | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "drop ss-not-full      | PrioritySampling.SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "keep ss-not-full      | PrioritySampling.SAMPLER_KEEP | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "drop 2 ss-not-full    | PrioritySampling.SAMPLER_DROP | 0             | 1                | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "user keep ss-not-full | PrioritySampling.USER_KEEP    | 1             | 0                | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "unset ss-full         | PrioritySampling.UNSET        | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "drop ss-full          | PrioritySampling.SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW          ",
    "keep ss-full          | PrioritySampling.SAMPLER_KEEP | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "drop 2 ss-full        | PrioritySampling.SAMPLER_DROP | 0             | 1                | true           | DROPPED_BUFFER_OVERFLOW          ",
    "user keep ss-full     | PrioritySampling.USER_KEEP    | 1             | 0                | true           | ENQUEUED_FOR_SERIALIZATION       "
  })
  void testFastLaneStrategyWithSpanSamplingQueue(
      @ConvertWith(PrioritySamplingConverter.class) int priority,
      int primaryOffers,
      int singleSpanOffers,
      boolean singleSpanFull,
      PublishResult expectedResult) {
    List<DDSpan> trace = Collections.emptyList();
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> secondary = mock(Queue.class);
    Queue<Object> spanSampling = mock(Queue.class);
    PrioritizationStrategy fastLane =
        Prioritization.FAST_LANE.create(primary, secondary, spanSampling, () -> false);
    when(primary.offer(trace)).thenReturn(true);
    when(spanSampling.offer(trace)).thenReturn(!singleSpanFull);

    PublishResult publishResult = fastLane.publish(mock(DDSpan.class), priority, trace);

    assertEquals(expectedResult, publishResult);
    verify(primary, times(primaryOffers)).offer(trace);
    verify(secondary, never()).offer(any()); // expect no traces sent to the secondary queue
    verify(spanSampling, times(singleSpanOffers)).offer(trace);
  }

  @SuppressWarnings("unchecked")
  @TableTest({
    "scenario  | priority                      | primaryOffers | singleSpanOffers | expectedResult                   ",
    "unset     | PrioritySampling.UNSET        | 1             | 0                | ENQUEUED_FOR_SERIALIZATION       ",
    "drop      | PrioritySampling.SAMPLER_DROP | 0             | 1                | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "keep      | PrioritySampling.SAMPLER_KEEP | 1             | 0                | ENQUEUED_FOR_SERIALIZATION       ",
    "drop 2    | PrioritySampling.SAMPLER_DROP | 0             | 1                | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING",
    "user keep | PrioritySampling.USER_KEEP    | 1             | 0                | ENQUEUED_FOR_SERIALIZATION       "
  })
  void testFastLaneWithActiveDroppingPolicySendToSingleSpanSampling(
      @ConvertWith(PrioritySamplingConverter.class) int priority,
      int primaryOffers,
      int singleSpanOffers,
      PublishResult expectedResult) {
    List<DDSpan> trace = Collections.emptyList();
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> secondary = mock(Queue.class);
    Queue<Object> spanSampling = mock(Queue.class);
    PrioritizationStrategy drop =
        Prioritization.FAST_LANE.create(primary, secondary, spanSampling, () -> true);
    when(primary.offer(trace)).thenReturn(true);
    when(spanSampling.offer(trace)).thenReturn(true);

    PublishResult publishResult = drop.publish(mock(DDSpan.class), priority, trace);

    assertEquals(expectedResult, publishResult);
    verify(primary, times(primaryOffers)).offer(trace);
    verify(secondary, never()).offer(trace);
    verify(spanSampling, times(singleSpanOffers)).offer(trace);
  }

  @SuppressWarnings("unchecked")
  @TableTest({
    "scenario                  | strategy  | forceKeep | singleSpanFull | expectedResult                   ",
    "force keep true full      | FAST_LANE | true      | true           | ENQUEUED_FOR_SERIALIZATION       ",
    "force keep false full     | FAST_LANE | false     | true           | DROPPED_BUFFER_OVERFLOW          ",
    "force keep true not full  | FAST_LANE | true      | false          | ENQUEUED_FOR_SERIALIZATION       ",
    "force keep false not full | FAST_LANE | false     | false          | ENQUEUED_FOR_SINGLE_SPAN_SAMPLING"
  })
  void testSpanSamplingDropStrategyRespectsForceKeep(
      Prioritization strategy,
      boolean forceKeep,
      boolean singleSpanFull,
      PublishResult expectedResult) {
    Queue<Object> primary = mock(Queue.class);
    Queue<Object> spanSampling = mock(Queue.class);
    PrioritizationStrategy drop = strategy.create(primary, null, spanSampling, () -> true);
    DDSpan root = mock(DDSpan.class);
    List<DDSpan> trace = Collections.singletonList(root);
    when(root.isForceKeep()).thenReturn(forceKeep);
    when(primary.offer(trace)).thenReturn(true);
    when(spanSampling.offer(trace)).thenReturn(!singleSpanFull);

    PublishResult publishResult = drop.publish(root, PrioritySampling.SAMPLER_DROP, trace);

    assertEquals(expectedResult, publishResult);
    verify(root).isForceKeep();
    verify(primary, times(forceKeep ? 1 : 0)).offer(trace);
    verify(spanSampling, times(forceKeep ? 0 : 1)).offer(trace);
    verifyNoMoreInteractions(root, primary, spanSampling);
  }
}
