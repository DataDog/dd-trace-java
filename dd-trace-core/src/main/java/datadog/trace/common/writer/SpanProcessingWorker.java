package datadog.trace.common.writer;

import static datadog.trace.util.AgentThreadFactory.AgentThread.SPAN_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import datadog.trace.common.sampling.SingleSpanSampler;
import datadog.trace.core.CoreSpan;
import java.util.List;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;

public class SpanProcessingWorker implements AutoCloseable {

  private final Thread samplingThread;
  private final SamplingHandler samplingHandler;
  private final MpscBlockingConsumerArrayQueue<Object> spanInQueue;
  private final MpscBlockingConsumerArrayQueue<Object> sampledSpansQueue;

  public static SpanProcessingWorker build(
      int capacity,
      MpscBlockingConsumerArrayQueue<Object> spanOutQueue,
      SingleSpanSampler singleSpanSampler) {
    if (singleSpanSampler == null) {
      return null;
    }
    return new SpanProcessingWorker(capacity, spanOutQueue);
  }

  private static final class SamplingHandler implements Runnable {

    @Override
    public void run() {
      // TODO consume from the queue
      // TODO filter spans by Single Span Sampling rules
      // TODO add necessary tags
      // TODO send sampled spans to the spanOutQueue
    }
  }
  // TODO pass the primaryQueue here for sending sampled spans to the Agent

  public SpanProcessingWorker(
      int capacity, MpscBlockingConsumerArrayQueue<Object> sampledSpansQueue) {
    // TODO read Single Span Sampling Rules
    this.samplingHandler = new SamplingHandler();
    this.samplingThread = newAgentThread(SPAN_PROCESSOR, samplingHandler);
    this.spanInQueue = new MpscBlockingConsumerArrayQueue<>(capacity);
    this.sampledSpansQueue = sampledSpansQueue;
  }

  public void start() {
    this.samplingThread.start();
  }

  @Override
  public void close() {
    samplingThread.interrupt();
    try {
      samplingThread.join(THREAD_JOIN_TIMOUT_MS);
    } catch (InterruptedException ignored) {
    }
  }

  public <T extends CoreSpan<T>> boolean publish(List<T> trace) {
    return spanInQueue.offer(trace);
  }
}
