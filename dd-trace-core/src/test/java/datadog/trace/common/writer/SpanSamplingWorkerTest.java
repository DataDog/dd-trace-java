package datadog.trace.common.writer;

import static datadog.trace.common.writer.SpanSamplingWorker.DefaultSpanSamplingWorker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.sampling.SingleSpanSampler;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

class SpanSamplingWorkerTest {

  @Test
  void sendOnlySampledSpansToSampledSpanQueue() throws Exception {
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    SpanSamplingWorker worker =
        SpanSamplingWorker.build(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false);
    worker.start();

    try {
      DDSpan span1 = mock(DDSpan.class);
      DDSpan span2 = mock(DDSpan.class);
      DDSpan span3 = mock(DDSpan.class);
      when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(true);
      when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(false);
      when(singleSpanSampler.setSamplingPriority(span3)).thenReturn(true);

      List<DDSpan> trace = Arrays.asList(span1, span2, span3);
      worker.getSpanSamplingQueue().offer(trace);

      assertEquals(Arrays.asList(span1, span3), ((LinkedBlockingDeque<?>) primaryQueue).take());
      assertEquals(Arrays.asList(span2), ((LinkedBlockingDeque<?>) secondaryQueue).take());
    } finally {
      worker.close();
    }
  }

  @Test
  void handleMultipleTraces() throws Exception {
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    SpanSamplingWorker worker =
        SpanSamplingWorker.build(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false);
    worker.start();

    try {
      DDSpan span1 = mock(DDSpan.class);
      DDSpan span2 = mock(DDSpan.class);
      DDSpan span3 = mock(DDSpan.class);
      when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(true);
      when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(false);
      when(singleSpanSampler.setSamplingPriority(span3)).thenReturn(true);

      DDSpan span4 = mock(DDSpan.class);
      DDSpan span5 = mock(DDSpan.class);
      when(singleSpanSampler.setSamplingPriority(span4)).thenReturn(true);
      when(singleSpanSampler.setSamplingPriority(span5)).thenReturn(false);

      worker.getSpanSamplingQueue().offer(Arrays.asList(span1, span2, span3));
      worker.getSpanSamplingQueue().offer(Arrays.asList(span4, span5));

      assertEquals(Arrays.asList(span1, span3), ((LinkedBlockingDeque<?>) primaryQueue).take());
      assertEquals(Arrays.asList(span2), ((LinkedBlockingDeque<?>) secondaryQueue).take());
      assertEquals(Arrays.asList(span4), ((LinkedBlockingDeque<?>) primaryQueue).take());
      assertEquals(Arrays.asList(span5), ((LinkedBlockingDeque<?>) secondaryQueue).take());
    } finally {
      worker.close();
    }
  }

  @Test
  void skipTracesWithNoSampledSpans() throws Exception {
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    SpanSamplingWorker worker =
        SpanSamplingWorker.build(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false);
    worker.start();

    try {
      DDSpan span1 = mock(DDSpan.class);
      DDSpan span2 = mock(DDSpan.class);
      DDSpan span3 = mock(DDSpan.class);
      when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(true);
      when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(false);
      when(singleSpanSampler.setSamplingPriority(span3)).thenReturn(true);

      DDSpan span4 = mock(DDSpan.class);
      DDSpan span5 = mock(DDSpan.class);
      when(singleSpanSampler.setSamplingPriority(span4)).thenReturn(false);
      when(singleSpanSampler.setSamplingPriority(span5)).thenReturn(false);

      DDSpan span6 = mock(DDSpan.class);
      DDSpan span7 = mock(DDSpan.class);
      when(singleSpanSampler.setSamplingPriority(span6)).thenReturn(true);
      when(singleSpanSampler.setSamplingPriority(span7)).thenReturn(true);

      assertTrue(worker.getSpanSamplingQueue().offer(Arrays.asList(span1, span2, span3)));
      assertTrue(worker.getSpanSamplingQueue().offer(Arrays.asList(span4, span5)));
      assertTrue(worker.getSpanSamplingQueue().offer(Arrays.asList(span6, span7)));

      assertEquals(Arrays.asList(span1, span3), ((LinkedBlockingDeque<?>) primaryQueue).take());
      assertEquals(Arrays.asList(span2), ((LinkedBlockingDeque<?>) secondaryQueue).take());
      assertEquals(Arrays.asList(span4, span5), ((LinkedBlockingDeque<?>) secondaryQueue).take());
      assertEquals(Arrays.asList(span6, span7), ((LinkedBlockingDeque<?>) primaryQueue).take());
    } finally {
      worker.close();
    }
  }

  @Test
  void ignoreEmptyTraces() throws Exception {
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    SpanSamplingWorker worker =
        SpanSamplingWorker.build(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false);
    worker.start();

    try {
      DDSpan span1 = mock(DDSpan.class);
      when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(true);

      assertTrue(worker.getSpanSamplingQueue().offer(new ArrayList<>()));
      assertTrue(worker.getSpanSamplingQueue().offer(Arrays.asList(span1)));

      assertEquals(Arrays.asList(span1), ((LinkedBlockingDeque<?>) primaryQueue).take());
      assertTrue(secondaryQueue.isEmpty());
    } finally {
      worker.close();
    }
  }

  @Test
  void updateDroppedTracesMetricWhenNoTracerSpansHaveBeenSampled() throws Exception {
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    int expectedTraces = 1;
    CountDownLatch latch = new CountDownLatch(expectedTraces);
    SpanSamplingWorker worker =
        new DefaultSpanSamplingWorker(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false) {
          @Override
          protected void afterOnEvent() {
            latch.countDown();
          }
        };
    worker.start();

    try {
      DDSpan span1 = mock(DDSpan.class);
      DDSpan span2 = mock(DDSpan.class);
      when(span1.samplingPriority()).thenReturn((int) PrioritySampling.USER_DROP);
      when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(false);
      when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(false);

      List<DDSpan> trace = Arrays.asList(span1, span2);
      assertTrue(worker.getSpanSamplingQueue().offer(trace));

      assertTrue(latch.await(10, TimeUnit.SECONDS));

      assertTrue(primaryQueue.isEmpty());
      assertEquals(trace, ((LinkedBlockingDeque<?>) secondaryQueue).take());

      verify(healthMetrics, times(1)).onPublish(trace, (int) PrioritySampling.USER_DROP);
      verify(healthMetrics, never())
          .onFailedPublish(
              org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
      verify(healthMetrics, never()).onPartialPublish(org.mockito.ArgumentMatchers.anyInt());
      verify(healthMetrics, times(2)).onSingleSpanUnsampled();
      verifyNoMoreInteractions(healthMetrics);
    } finally {
      worker.close();
    }
  }

  @Test
  void updateDroppedTracesMetricWhenPrimaryQueueIsFull() throws Exception {
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(1);
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    primaryQueue.offer(new ArrayList<>()); // occupy the entire queue
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    int expectedTraces = 1;
    CountDownLatch latch = new CountDownLatch(expectedTraces);
    SpanSamplingWorker worker =
        new DefaultSpanSamplingWorker(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false) {
          @Override
          protected void afterOnEvent() {
            latch.countDown();
          }
        };
    worker.start();

    try {
      DDSpan span1 = mock(DDSpan.class);
      DDSpan span2 = mock(DDSpan.class);
      when(span1.samplingPriority()).thenReturn((int) PrioritySampling.SAMPLER_DROP);
      when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(false);
      when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(true);

      List<DDSpan> trace = Arrays.asList(span1, span2);
      assertTrue(worker.getSpanSamplingQueue().offer(trace));

      assertTrue(latch.await(10, TimeUnit.SECONDS));

      assertTrue(secondaryQueue.isEmpty());

      verify(healthMetrics, times(1))
          .onFailedPublish(
              org.mockito.ArgumentMatchers.eq((int) PrioritySampling.SAMPLER_DROP),
              org.mockito.ArgumentMatchers.anyInt());
      verify(healthMetrics, never())
          .onPublish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
      verify(healthMetrics, never()).onPartialPublish(org.mockito.ArgumentMatchers.anyInt());
      verify(healthMetrics, times(1)).onSingleSpanUnsampled();
      verify(healthMetrics, times(1)).onSingleSpanSample();
      verifyNoMoreInteractions(healthMetrics);
    } finally {
      worker.close();
    }
  }

  @Test
  void updatePublishedTracesMetricWhenAllTraceSpansHaveBeenSampled() throws Exception {
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    int expectedTraces = 1;
    CountDownLatch latch = new CountDownLatch(expectedTraces);
    SpanSamplingWorker worker =
        new DefaultSpanSamplingWorker(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false) {
          @Override
          protected void afterOnEvent() {
            latch.countDown();
          }
        };
    worker.start();

    try {
      DDSpan span1 = mock(DDSpan.class);
      DDSpan span2 = mock(DDSpan.class);
      when(span1.samplingPriority()).thenReturn((int) PrioritySampling.SAMPLER_DROP);
      when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(true);
      when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(true);

      List<DDSpan> trace = Arrays.asList(span1, span2);
      assertTrue(worker.getSpanSamplingQueue().offer(trace));

      assertTrue(latch.await(10, TimeUnit.SECONDS));

      assertEquals(trace, ((LinkedBlockingDeque<?>) primaryQueue).take());
      assertTrue(secondaryQueue.isEmpty());

      verify(healthMetrics, times(1)).onPublish(trace, (int) PrioritySampling.SAMPLER_DROP);
      verify(healthMetrics, never())
          .onFailedPublish(
              org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
      verify(healthMetrics, never()).onPartialPublish(org.mockito.ArgumentMatchers.anyInt());
      verify(healthMetrics, times(2)).onSingleSpanSample();
      verifyNoMoreInteractions(healthMetrics);
    } finally {
      worker.close();
    }
  }

  @Test
  void updatePartialTracesMetricWhenSomeTraceSpansHaveBeenDropped() throws Exception {
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    int expectedTraces = 1;
    CountDownLatch latch = new CountDownLatch(expectedTraces);
    SpanSamplingWorker worker =
        new DefaultSpanSamplingWorker(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false) {
          @Override
          protected void afterOnEvent() {
            latch.countDown();
          }
        };
    worker.start();

    try {
      DDSpan span1 = mock(DDSpan.class);
      DDSpan span2 = mock(DDSpan.class);
      DDSpan span3 = mock(DDSpan.class);
      when(span1.samplingPriority()).thenReturn((int) PrioritySampling.SAMPLER_DROP);
      when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(false);
      when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(true);
      when(singleSpanSampler.setSamplingPriority(span3)).thenReturn(false);

      List<DDSpan> trace = Arrays.asList(span1, span2, span3);
      assertTrue(worker.getSpanSamplingQueue().offer(trace));

      assertTrue(latch.await(10, TimeUnit.SECONDS));

      assertEquals(Arrays.asList(span2), ((LinkedBlockingDeque<?>) primaryQueue).take());
      assertEquals(Arrays.asList(span1, span3), ((LinkedBlockingDeque<?>) secondaryQueue).take());

      verify(healthMetrics, times(1)).onPublish(trace, (int) PrioritySampling.SAMPLER_DROP);
      verify(healthMetrics, never()).onPartialPublish(org.mockito.ArgumentMatchers.anyInt());
      verify(healthMetrics, never())
          .onFailedPublish(
              org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
      verify(healthMetrics, times(1)).onSingleSpanSample();
      verify(healthMetrics, times(2)).onSingleSpanUnsampled();
      verifyNoMoreInteractions(healthMetrics);
    } finally {
      worker.close();
    }
  }

  @TableTest({
    "scenario      | droppingPolicy | secondaryQueueIsFull",
    "policy active | true           | false               ",
    "queue full    | false          | true                ",
    "both          | true           | true                "
  })
  @ParameterizedTest(name = "[{index}] {0}")
  void updatePartialTracesMetricWhenDroppedAndSecondaryQueueFullOrDroppingPolicyActive(
      boolean droppingPolicy, boolean secondaryQueueIsFull) throws Exception {
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(secondaryQueueIsFull ? 1 : 10);
    if (secondaryQueueIsFull) {
      secondaryQueue.offer(new ArrayList<>());
    }
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    boolean finalDroppingPolicy = droppingPolicy;
    SpanSamplingWorker worker =
        new DefaultSpanSamplingWorker(
            10,
            primaryQueue,
            secondaryQueue,
            singleSpanSampler,
            healthMetrics,
            () -> finalDroppingPolicy);
    worker.start();

    try {
      DDSpan span1 = mock(DDSpan.class);
      DDSpan span2 = mock(DDSpan.class);
      DDSpan span3 = mock(DDSpan.class);
      when(span1.samplingPriority()).thenReturn((int) PrioritySampling.SAMPLER_DROP);
      when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(false);
      when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(true);
      when(singleSpanSampler.setSamplingPriority(span3)).thenReturn(false);

      List<DDSpan> trace = Arrays.asList(span1, span2, span3);
      assertTrue(worker.getSpanSamplingQueue().offer(trace));

      assertEquals(Arrays.asList(span2), ((LinkedBlockingDeque<?>) primaryQueue).take());

      verify(healthMetrics, times(1)).onPartialPublish(2);
      verify(healthMetrics, never())
          .onFailedPublish(
              org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
      verify(healthMetrics, never())
          .onPublish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
      verify(healthMetrics, times(1)).onSingleSpanSample();
      verify(healthMetrics, times(2)).onSingleSpanUnsampled();
      verifyNoMoreInteractions(healthMetrics);
    } finally {
      worker.close();
    }
  }

  @TableTest({
    "scenario      | droppingPolicy | secondaryQueueIsFull",
    "policy active | true           | false               ",
    "queue full    | false          | true                ",
    "both          | true           | true                "
  })
  @ParameterizedTest(name = "[{index}] {0}")
  void updateFailedPublishMetricWhenAllSpansDroppedAndSecondaryQueueFullOrDroppingPolicyActive(
      boolean droppingPolicy, boolean secondaryQueueIsFull) throws Exception {
    Queue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    Queue<Object> secondaryQueue = new LinkedBlockingDeque<>(secondaryQueueIsFull ? 1 : 10);
    if (secondaryQueueIsFull) {
      secondaryQueue.offer(new ArrayList<>());
    }
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    int expectedTraces = 1;
    CountDownLatch latch = new CountDownLatch(expectedTraces);
    boolean finalDroppingPolicy = droppingPolicy;
    SpanSamplingWorker worker =
        new DefaultSpanSamplingWorker(
            10,
            primaryQueue,
            secondaryQueue,
            singleSpanSampler,
            healthMetrics,
            () -> finalDroppingPolicy) {
          @Override
          protected void afterOnEvent() {
            latch.countDown();
          }
        };
    worker.start();

    try {
      DDSpan span1 = mock(DDSpan.class);
      DDSpan span2 = mock(DDSpan.class);
      when(span1.samplingPriority()).thenReturn((int) PrioritySampling.SAMPLER_DROP);
      when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(false);
      when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(false);

      List<DDSpan> trace = Arrays.asList(span1, span2);
      assertTrue(worker.getSpanSamplingQueue().offer(trace));

      assertTrue(latch.await(10, TimeUnit.SECONDS));

      verify(healthMetrics, times(1))
          .onFailedPublish(
              org.mockito.ArgumentMatchers.eq((int) PrioritySampling.SAMPLER_DROP),
              org.mockito.ArgumentMatchers.anyInt());
      verify(healthMetrics, never()).onPartialPublish(org.mockito.ArgumentMatchers.anyInt());
      verify(healthMetrics, never())
          .onPublish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
      verify(healthMetrics, times(2)).onSingleSpanUnsampled();
      verifyNoMoreInteractions(healthMetrics);
    } finally {
      worker.close();
    }
  }
}
