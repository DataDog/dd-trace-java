package datadog.trace.common.writer;

import static datadog.trace.util.AgentThreadFactory.AgentThread.SPAN_SAMPLING_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.communication.ddagent.DroppingPolicy;
import datadog.trace.common.sampling.SingleSpanSampler;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.util.queue.MpscBlockingConsumerArrayQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface SpanSamplingWorker extends AutoCloseable {
  static SpanSamplingWorker build(
      int capacity,
      Queue<Object> primaryQueue,
      Queue<Object> secondaryQueue,
      SingleSpanSampler singleSpanSampler,
      HealthMetrics healthMetrics,
      DroppingPolicy droppingPolicy) {
    if (singleSpanSampler == null) {
      return new NoopSpanSamplingWorker();
    }
    return new DefaultSpanSamplingWorker(
        capacity, primaryQueue, secondaryQueue, singleSpanSampler, healthMetrics, droppingPolicy);
  }

  void start();

  Queue<Object> getSpanSamplingQueue();

  @Override
  void close();

  class DefaultSpanSamplingWorker implements SpanSamplingWorker {

    private static final Logger log = LoggerFactory.getLogger(SpanSamplingWorker.class);

    private final Thread spanSamplingThread;
    private final SamplingHandler samplingHandler;
    private final MpscBlockingConsumerArrayQueue<Object> spanSamplingQueue;
    private final Queue<Object> primaryQueue;
    private final Queue<Object> secondaryQueue;
    private final SingleSpanSampler singleSpanSampler;
    private final HealthMetrics healthMetrics;

    private final DroppingPolicy droppingPolicy;

    protected DefaultSpanSamplingWorker(
        int capacity,
        Queue<Object> primaryQueue,
        Queue<Object> secondaryQueue,
        SingleSpanSampler singleSpanSampler,
        HealthMetrics healthMetrics,
        DroppingPolicy droppingPolicy) {
      this.samplingHandler = new SamplingHandler();
      this.spanSamplingThread = newAgentThread(SPAN_SAMPLING_PROCESSOR, samplingHandler);
      this.spanSamplingQueue = new MpscBlockingConsumerArrayQueue<>(capacity);
      this.primaryQueue = primaryQueue;
      this.secondaryQueue = secondaryQueue;
      this.singleSpanSampler = singleSpanSampler;
      this.healthMetrics = healthMetrics;
      this.droppingPolicy = droppingPolicy;
    }

    @Override
    public void start() {
      this.spanSamplingThread.start();
    }

    @Override
    public void close() {
      spanSamplingThread.interrupt();
      try {
        spanSamplingThread.join(THREAD_JOIN_TIMOUT_MS);
      } catch (InterruptedException ignored) {
      }
    }

    @Override
    public Queue<Object> getSpanSamplingQueue() {
      return spanSamplingQueue;
    }

    protected void afterOnEvent() {
      // this method is used in tests only
    }

    private final class SamplingHandler implements Runnable {

      @Override
      public void run() {
        try {
          runDutyCycle();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }

      private void runDutyCycle() throws InterruptedException {
        Thread thread = Thread.currentThread();
        while (!thread.isInterrupted()) {
          consumeFromInputQueue();
        }
      }

      private void consumeFromInputQueue() throws InterruptedException {
        Object event = spanSamplingQueue.poll(100, MILLISECONDS);
        if (null != event) {
          onEvent(event);
          consumeBatch(spanSamplingQueue);
        }
      }

      @SuppressWarnings("unchecked")
      public void onEvent(Object event) {
        if (event instanceof List) {
          List<DDSpan> trace = (List<DDSpan>) event;

          if (trace.isEmpty()) {
            // protect against empty traces
            log.warn("SingleSamplingWorker has received an empty trace.");
            return;
          }

          ArrayList<DDSpan> sampledSpans = new ArrayList<>(trace.size());
          ArrayList<DDSpan> unsampledSpans = new ArrayList<>(trace.size());
          for (DDSpan span : trace) {
            if (singleSpanSampler.setSamplingPriority(span)) {
              sampledSpans.add(span);
              healthMetrics.onSingleSpanSample();
            } else {
              unsampledSpans.add(span);
              healthMetrics.onSingleSpanUnsampled();
            }
          }

          int samplingPriority = trace.get(0).samplingPriority();
          if (sampledSpans.size() > 0 && !primaryQueue.offer(sampledSpans)) {
            // couldn't send sampled spans because the queue is full, count entire trace as dropped
            healthMetrics.onFailedPublish(samplingPriority, trace.size());
            log.debug(
                "Sampled spans written to overfilled buffer after single span sampling. Counted but dropping trace: {}",
                trace);
          } else if (unsampledSpans.size() > 0
              && (droppingPolicy.active() || !secondaryQueue.offer(unsampledSpans))) {
            if (sampledSpans.isEmpty()) {
              // dropped all spans because none of the spans sampled
              healthMetrics.onFailedPublish(samplingPriority, unsampledSpans.size());
              log.debug(
                  "Trace is empty. None of the spans have been sampled by single span sampling. Counted but dropping trace: {}",
                  trace);
            } else {
              healthMetrics.onPartialPublish(unsampledSpans.size());
              log.debug(
                  "Unsampled spans dropped after single span sampling because Dropping Policy is active (droppingPolicy.active()={}) or the queue is full. Counted partial trace: {}",
                  droppingPolicy.active(),
                  sampledSpans);
            }
          } else {
            log.debug("Entire trace has been published: {}", trace);
            healthMetrics.onPublish(trace, samplingPriority);
          }
          afterOnEvent();
        }
      }

      private void consumeBatch(MpscBlockingConsumerArrayQueue<Object> queue) {
        queue.drain(this::onEvent, queue.size());
      }
    }
  }

  class NoopSpanSamplingWorker implements SpanSamplingWorker {
    @Override
    public void start() {}

    @Override
    public Queue<Object> getSpanSamplingQueue() {
      return null;
    }

    @Override
    public void close() {}
  }
}
