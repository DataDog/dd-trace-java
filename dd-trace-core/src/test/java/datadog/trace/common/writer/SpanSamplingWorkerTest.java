package datadog.trace.common.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.sampling.SingleSpanSampler;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class SpanSamplingWorkerTest extends DDJavaSpecification {

  @Test
  void testSendOnlySampledSpansToTheSampledSpanQueue() throws InterruptedException {
    BlockingQueue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    BlockingQueue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    SpanSamplingWorker worker =
        SpanSamplingWorker.build(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false);
    worker.start();
    DDSpan span1 = mock(DDSpan.class);
    DDSpan span2 = mock(DDSpan.class);
    DDSpan span3 = mock(DDSpan.class);
    when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(true);
    when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(false);
    when(singleSpanSampler.setSamplingPriority(span3)).thenReturn(true);

    worker.getSpanSamplingQueue().offer(Arrays.asList(span1, span2, span3));

    assertEquals(Arrays.asList(span1, span3), primaryQueue.take());
    assertEquals(Arrays.asList(span2), secondaryQueue.take());

    worker.close();
  }

  @Test
  void testHandleMultipleTraces() throws InterruptedException {
    BlockingQueue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    BlockingQueue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    SpanSamplingWorker worker =
        SpanSamplingWorker.build(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false);
    worker.start();
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

    assertEquals(Arrays.asList(span1, span3), primaryQueue.take());
    assertEquals(Arrays.asList(span2), secondaryQueue.take());
    assertEquals(Arrays.asList(span4), primaryQueue.take());
    assertEquals(Arrays.asList(span5), secondaryQueue.take());

    worker.close();
  }

  @Test
  void testSkipTracesWithNoSampledSpans() throws InterruptedException {
    BlockingQueue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    BlockingQueue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    SpanSamplingWorker worker =
        SpanSamplingWorker.build(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false);
    worker.start();
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

    assertEquals(Arrays.asList(span1, span3), primaryQueue.take());
    assertEquals(Arrays.asList(span2), secondaryQueue.take());
    assertEquals(Arrays.asList(span4, span5), secondaryQueue.take());
    assertEquals(Arrays.asList(span6, span7), primaryQueue.take());

    worker.close();
  }

  @Test
  void testIgnoreEmptyTraces() throws InterruptedException {
    BlockingQueue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    BlockingQueue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    SpanSamplingWorker worker =
        SpanSamplingWorker.build(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false);
    worker.start();
    DDSpan span1 = mock(DDSpan.class);
    when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(true);

    assertTrue(worker.getSpanSamplingQueue().offer(java.util.Collections.emptyList()));
    assertTrue(worker.getSpanSamplingQueue().offer(Arrays.asList(span1)));

    assertEquals(Arrays.asList(span1), primaryQueue.take());
    assertTrue(secondaryQueue.isEmpty());

    worker.close();
  }

  @Test
  void testUpdateDroppedTracesMetricWhenNoTracerSpansHaveBeenSampled() throws InterruptedException {
    BlockingQueue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    BlockingQueue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    CountDownLatch latch = new CountDownLatch(1);
    SpanSamplingWorker worker =
        new SpanSamplingWorker.DefaultSpanSamplingWorker(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false) {
          @Override
          protected void afterOnEvent() {
            latch.countDown();
          }
        };
    worker.start();
    DDSpan span1 = mock(DDSpan.class);
    DDSpan span2 = mock(DDSpan.class);
    when(span1.samplingPriority()).thenReturn((int) PrioritySampling.USER_DROP);
    when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(false);
    when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(false);
    List<DDSpan> trace = Arrays.asList(span1, span2);

    assertTrue(worker.getSpanSamplingQueue().offer(trace));

    // wait for processing
    assertTrue(latch.await(10, TimeUnit.SECONDS));
    assertTrue(primaryQueue.isEmpty());
    assertEquals(trace, secondaryQueue.take());
    verify(healthMetrics).onPublish(trace, PrioritySampling.USER_DROP);
    verify(healthMetrics, never()).onFailedPublish(anyInt(), anyInt());
    verify(healthMetrics, never()).onPartialPublish(anyInt());

    worker.close();
  }

  @Test
  void testUpdateDroppedTracesMetricWhenPrimaryQueueIsFull() throws InterruptedException {
    BlockingQueue<Object> primaryQueue = new LinkedBlockingDeque<>(1);
    BlockingQueue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    primaryQueue.offer(java.util.Collections.emptyList()); // occupy the entire queue
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    CountDownLatch latch = new CountDownLatch(1);
    SpanSamplingWorker worker =
        new SpanSamplingWorker.DefaultSpanSamplingWorker(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false) {
          @Override
          protected void afterOnEvent() {
            latch.countDown();
          }
        };
    worker.start();
    DDSpan span1 = mock(DDSpan.class);
    DDSpan span2 = mock(DDSpan.class);
    when(span1.samplingPriority()).thenReturn((int) PrioritySampling.SAMPLER_DROP);
    when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(false);
    when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(true);
    List<DDSpan> trace = Arrays.asList(span1, span2);

    assertTrue(worker.getSpanSamplingQueue().offer(trace));

    // wait for processing
    assertTrue(latch.await(10, TimeUnit.SECONDS));
    assertTrue(secondaryQueue.isEmpty());
    verify(healthMetrics).onFailedPublish(eq((int) PrioritySampling.SAMPLER_DROP), anyInt());
    verify(healthMetrics, never()).onPublish(any(), anyInt());
    verify(healthMetrics, never()).onPartialPublish(anyInt());

    worker.close();
  }

  @Test
  void testUpdatePublishedTracesMetricWhenAllTraceSpansHaveBeenSampled()
      throws InterruptedException {
    BlockingQueue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    BlockingQueue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    SpanSamplingWorker worker =
        SpanSamplingWorker.build(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false);
    worker.start();
    DDSpan span1 = mock(DDSpan.class);
    DDSpan span2 = mock(DDSpan.class);
    when(span1.samplingPriority()).thenReturn((int) PrioritySampling.SAMPLER_DROP);
    when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(true);
    when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(true);
    List<DDSpan> trace = Arrays.asList(span1, span2);

    assertTrue(worker.getSpanSamplingQueue().offer(trace));

    // take() blocks until worker has put spans in primaryQueue
    assertEquals(trace, primaryQueue.take());
    assertTrue(secondaryQueue.isEmpty());
    // use timeout to wait for healthMetrics to be called after queue operations
    verify(healthMetrics, timeout(5000)).onPublish(trace, PrioritySampling.SAMPLER_DROP);
    verify(healthMetrics, never()).onFailedPublish(anyInt(), anyInt());
    verify(healthMetrics, never()).onPartialPublish(anyInt());

    worker.close();
  }

  @Test
  void testUpdatePartialTracesMetricWhenSomeSpansDroppedAndSentToSecondaryQueue()
      throws InterruptedException {
    BlockingQueue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    BlockingQueue<Object> secondaryQueue = new LinkedBlockingDeque<>(10);
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    SpanSamplingWorker worker =
        SpanSamplingWorker.build(
            10, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, () -> false);
    worker.start();
    DDSpan span1 = mock(DDSpan.class);
    DDSpan span2 = mock(DDSpan.class);
    DDSpan span3 = mock(DDSpan.class);
    when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(false);
    when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(true);
    when(singleSpanSampler.setSamplingPriority(span3)).thenReturn(false);
    List<DDSpan> trace = Arrays.asList(span1, span2, span3);

    assertTrue(worker.getSpanSamplingQueue().offer(trace));

    // take() blocks until worker has put spans in queues
    assertEquals(Arrays.asList(span2), primaryQueue.take());
    assertEquals(Arrays.asList(span1, span3), secondaryQueue.take());
    // use timeout to wait for healthMetrics to be called after queue operations
    verify(healthMetrics, timeout(5000)).onPublish(trace, PrioritySampling.SAMPLER_DROP);
    verify(healthMetrics, never()).onPartialPublish(anyInt());
    verify(healthMetrics, never()).onFailedPublish(anyInt(), anyInt());

    worker.close();
  }

  @TableTest({
    "scenario                   | droppingPolicy | secondaryQueueIsFull",
    "dropping active            | true           | false               ",
    "secondary queue full       | false          | true                ",
    "dropping active+full queue | true           | true                "
  })
  void testUpdatePartialTracesMetricWhenSpansDroppedAndSecondaryQueueFullOrDroppingPolicyActive(
      boolean droppingPolicy, boolean secondaryQueueIsFull) throws InterruptedException {
    BlockingQueue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    BlockingQueue<Object> secondaryQueue = new LinkedBlockingDeque<>(secondaryQueueIsFull ? 1 : 10);
    if (secondaryQueueIsFull) {
      // occupy the entire queue
      secondaryQueue.offer(java.util.Collections.emptyList());
    }
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    SpanSamplingWorker worker =
        new SpanSamplingWorker.DefaultSpanSamplingWorker(
            10,
            primaryQueue,
            secondaryQueue,
            singleSpanSampler,
            healthMetrics,
            () -> droppingPolicy);
    worker.start();
    DDSpan span1 = mock(DDSpan.class);
    DDSpan span2 = mock(DDSpan.class);
    DDSpan span3 = mock(DDSpan.class);
    when(span1.samplingPriority()).thenReturn((int) PrioritySampling.SAMPLER_DROP);
    when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(false);
    when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(true);
    when(singleSpanSampler.setSamplingPriority(span3)).thenReturn(false);

    assertTrue(worker.getSpanSamplingQueue().offer(Arrays.asList(span1, span2, span3)));

    // take() blocks until worker has put span2 in primaryQueue
    assertEquals(Arrays.asList(span2), primaryQueue.take());
    // use timeout to wait for healthMetrics to be called after queue operations
    verify(healthMetrics, timeout(5000)).onPartialPublish(2);
    verify(healthMetrics, never()).onFailedPublish(anyInt(), anyInt());
    verify(healthMetrics, never()).onPublish(any(), anyInt());

    worker.close();
  }

  @TableTest({
    "scenario                   | droppingPolicy | secondaryQueueIsFull",
    "dropping active            | true           | false               ",
    "secondary queue full       | false          | true                ",
    "dropping active+full queue | true           | true                "
  })
  void testUpdateFailedPublishMetricWhenAllSpansDroppedAndSecondaryQueueFullOrDroppingPolicyActive(
      boolean droppingPolicy, boolean secondaryQueueIsFull) throws InterruptedException {
    BlockingQueue<Object> primaryQueue = new LinkedBlockingDeque<>(10);
    BlockingQueue<Object> secondaryQueue = new LinkedBlockingDeque<>(secondaryQueueIsFull ? 1 : 10);
    if (secondaryQueueIsFull) {
      // occupy the entire queue
      secondaryQueue.offer(java.util.Collections.emptyList());
    }
    SingleSpanSampler singleSpanSampler = mock(SingleSpanSampler.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    CountDownLatch latch = new CountDownLatch(1);
    SpanSamplingWorker worker =
        new SpanSamplingWorker.DefaultSpanSamplingWorker(
            10,
            primaryQueue,
            secondaryQueue,
            singleSpanSampler,
            healthMetrics,
            () -> droppingPolicy) {
          @Override
          protected void afterOnEvent() {
            latch.countDown();
          }
        };
    worker.start();
    DDSpan span1 = mock(DDSpan.class);
    DDSpan span2 = mock(DDSpan.class);
    when(span1.samplingPriority()).thenReturn((int) PrioritySampling.SAMPLER_DROP);
    when(singleSpanSampler.setSamplingPriority(span1)).thenReturn(false);
    when(singleSpanSampler.setSamplingPriority(span2)).thenReturn(false);

    assertTrue(worker.getSpanSamplingQueue().offer(Arrays.asList(span1, span2)));

    // wait for processing via latch
    assertTrue(latch.await(10, TimeUnit.SECONDS));
    verify(healthMetrics).onFailedPublish(eq((int) PrioritySampling.SAMPLER_DROP), anyInt());
    verify(healthMetrics, never()).onPartialPublish(anyInt());
    verify(healthMetrics, never()).onPublish(any(), anyInt());

    worker.close();
  }
}
