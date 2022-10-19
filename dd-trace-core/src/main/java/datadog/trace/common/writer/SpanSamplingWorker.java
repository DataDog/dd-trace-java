package datadog.trace.common.writer;

import static datadog.trace.util.AgentThreadFactory.AgentThread.SPAN_SAMPLING_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.common.sampling.SingleSpanSampler;
import datadog.trace.core.DDSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;

public class SpanSamplingWorker implements AutoCloseable {

  private final Thread spanSamplingThread;
  private final SamplingHandler samplingHandler;
  private final MpscBlockingConsumerArrayQueue<Object> spanSamplingQueue;
  private final Queue<Object> sampledSpansQueue;
  private final SingleSpanSampler singleSpanSampler;

  public static SpanSamplingWorker build(
      int capacity, Queue<Object> sampledSpansQueue, SingleSpanSampler singleSpanSampler) {
    if (singleSpanSampler == null) {
      return null;
    }
    return new SpanSamplingWorker(capacity, sampledSpansQueue, singleSpanSampler);
  }

  private SpanSamplingWorker(
      int capacity, Queue<Object> sampledSpansQueue, SingleSpanSampler singleSpanSampler) {
    this.samplingHandler = new SamplingHandler();
    this.spanSamplingThread = newAgentThread(SPAN_SAMPLING_PROCESSOR, samplingHandler);
    this.spanSamplingQueue = new MpscBlockingConsumerArrayQueue<>(capacity);
    this.sampledSpansQueue = sampledSpansQueue;
    this.singleSpanSampler = singleSpanSampler;
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
        ArrayList<DDSpan> sampledSpans = new ArrayList<>(trace.size());
        for (DDSpan span : trace) {
          if (singleSpanSampler.setSamplingPriority(span)) {
            sampledSpans.add(span);
          } // else ignore dropped spans
        }
        if (sampledSpans.size() > 0 && !sampledSpansQueue.offer(sampledSpans)) {
          // TODO should decrement sent traces and increment dropped traces/spans counters (see
          // datadog.trace.common.writer.RemoteWriter.write)
        }
      }
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
