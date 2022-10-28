package datadog.trace.common.writer;

import static datadog.trace.util.AgentThreadFactory.AgentThread.SPAN_SAMPLING_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.common.sampling.SingleSpanSampler;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpanSamplingWorker implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SpanSamplingWorker.class);

  private final Thread spanSamplingThread;
  private final SamplingHandler samplingHandler;
  private final MpscBlockingConsumerArrayQueue<Object> spanSamplingQueue;
  private final Queue<Object> sampledSpansQueue;
  private final SingleSpanSampler singleSpanSampler;
  private final HealthMetrics healthMetrics;

  public static SpanSamplingWorker build(
      int capacity,
      Queue<Object> sampledSpansQueue,
      SingleSpanSampler singleSpanSampler,
      HealthMetrics healthMetrics) {
    if (singleSpanSampler == null) {
      return null;
    }
    return new SpanSamplingWorker(capacity, sampledSpansQueue, singleSpanSampler, healthMetrics);
  }

  protected SpanSamplingWorker(
      int capacity,
      Queue<Object> sampledSpansQueue,
      SingleSpanSampler singleSpanSampler,
      HealthMetrics healthMetrics) {
    this.samplingHandler = new SamplingHandler();
    this.spanSamplingThread = newAgentThread(SPAN_SAMPLING_PROCESSOR, samplingHandler);
    this.spanSamplingQueue = new MpscBlockingConsumerArrayQueue<>(capacity);
    this.sampledSpansQueue = sampledSpansQueue;
    this.singleSpanSampler = singleSpanSampler;
    this.healthMetrics = healthMetrics;
  }

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

  public Queue<Object> getSpanSamplingQueue() {
    return spanSamplingQueue;
  }

  protected void afterOnEvent() {
    // this method is used in tests only
  }

  private final class SamplingHandler implements Runnable, MessagePassingQueue.Consumer<Object> {

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
        for (DDSpan span : trace) {
          if (singleSpanSampler.setSamplingPriority(span)) {
            sampledSpans.add(span);
          } // else ignore dropped spans
        }

        int samplingPriority = trace.get(0).samplingPriority();
        if (sampledSpans.size() == 0) {
          healthMetrics.onFailedPublish(samplingPriority);
          log.debug(
              "Trace was empty after single span sampling. Counted but dropping trace: {}", trace);
        } else if (!sampledSpansQueue.offer(sampledSpans)) {
          // dropped all spans or couldn't send sampled spans because the queue is full
          healthMetrics.onFailedPublish(samplingPriority);
          log.debug(
              "Trace written to overfilled buffer after single span sampling. Counted but dropping trace: {}",
              trace);
        } else if (sampledSpans.size() < trace.size()) {
          int droppedSpans = trace.size() - sampledSpans.size();
          healthMetrics.onPartialPublish(droppedSpans);
          log.debug(
              "Partial trace published after single span sampling. Original trace: {}. Published trace: {}",
              trace,
              sampledSpans);
        } else {
          // all spans are sampled, count as an entire published trace
          healthMetrics.onPublish(sampledSpans, samplingPriority);
        }
      }
      afterOnEvent();
    }

    private void consumeBatch(MessagePassingQueue<Object> queue) {
      queue.drain(this, queue.size());
    }

    @Override
    public void accept(Object event) {
      onEvent(event);
    }
  }
}
