package datadog.trace.common.writer;

import static datadog.trace.util.AgentThreadFactory.AgentThread.SPAN_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import datadog.trace.core.CoreSpan;
import java.util.List;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;

public class SpanProcessingWorker implements AutoCloseable {

  private final Thread samplingThread;
  private final SamplingHandler samplingHandler;
  private final MpscBlockingConsumerArrayQueue<Object> spanQueue;
  private final MpscBlockingConsumerArrayQueue<Object> primaryQueue;

  public static SpanProcessingWorker build(
      int capacity, MpscBlockingConsumerArrayQueue<Object> primaryQueue) {
    // TODO return null when Single Span Sampling rules are empty
    return new SpanProcessingWorker(capacity, primaryQueue);
  }

  private static final class SamplingHandler implements Runnable {

    @Override
    public void run() {
      // TODO consume from the queue and filter spans by Single Span Sampling rules
      // TODO add necessary tags
      // TODO send sampled spans to the primaryQueue
    }
  }
  // TODO pass the primaryQueue here for sending sampled spans to the Agent

  public SpanProcessingWorker(int capacity, MpscBlockingConsumerArrayQueue<Object> primaryQueue) {
    // TODO read Single Span Sampling Rules
    this.samplingHandler = new SamplingHandler();
    this.samplingThread = newAgentThread(SPAN_PROCESSOR, samplingHandler);
    this.spanQueue = new MpscBlockingConsumerArrayQueue<>(capacity);
    this.primaryQueue = primaryQueue;
  }

  public void start() {
    this.samplingThread.start();
  }

  @Override
  public void close() throws Exception {
    samplingThread.interrupt();
    try {
      samplingThread.join(THREAD_JOIN_TIMOUT_MS);
    } catch (InterruptedException ignored) {
    }
  }

  public <T extends CoreSpan<T>> boolean publish(List<T> trace) {
    return spanQueue.offer(trace);
  }
}
