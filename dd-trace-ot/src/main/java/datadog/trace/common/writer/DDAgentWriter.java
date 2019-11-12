package datadog.trace.common.writer;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClient;
import datadog.opentracing.DDSpan;
import datadog.opentracing.DDTraceOTInfo;
import datadog.trace.common.util.DaemonThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static datadog.trace.api.Config.*;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * This writer buffers traces and sends them to the provided DDApi instance.
 *
 * <p>Written traces are passed off to a disruptor so as to avoid blocking the application's thread.
 * If a flood of traces arrives that exceeds the disruptor ring size, the traces exceeding the
 * threshold will be counted and sampled.
 */
@Slf4j
public class DDAgentWriter implements Writer {
  private static final int DISRUPTOR_BUFFER_SIZE = 8192;
  private static final int FLUSH_PAYLOAD_BYTES = 5_000_000; // 5 MB
  private static final int FLUSH_PAYLOAD_DELAY = 1; // 1/second

  private static final EventTranslatorOneArg<TraceEvent, List<DDSpan>> TRANSLATOR =
      new EventTranslatorOneArg<TraceEvent, List<DDSpan>>() {
        @Override
        public void translateTo(
            final TraceEvent event, final long sequence, final List<DDSpan> trace) {
          event.trace = trace;
        }
      };
  private static final EventTranslator<TraceEvent> FLUSH_TRANSLATOR =
      new EventTranslator<TraceEvent>() {
        @Override
        public void translateTo(final TraceEvent event, final long sequence) {
          event.shouldFlush = true;
        }
      };

  private static final ThreadFactory DISRUPTOR_THREAD_FACTORY =
      new DaemonThreadFactory("dd-trace-disruptor");

  private static final ThreadFactory SENDER_THREAD_FACTORY =
      new DaemonThreadFactory("dd-trace-writer");

  private final DDApi api;
  private final Disruptor<TraceEvent> disruptor;

  private final BlockingQueue<DDApi.Request> requestQueue = new ArrayBlockingQueue<>(16);
  private final Sender sender;
  private final Thread senderThread;

  private final AtomicInteger droppedCount = new AtomicInteger(0);

  private final Phaser apiPhaser;
  private volatile boolean running = false;

  private final Monitor monitor;

  public DDAgentWriter() {
    this(
        new DDApi(DEFAULT_AGENT_HOST, DEFAULT_TRACE_AGENT_PORT, DEFAULT_AGENT_UNIX_DOMAIN_SOCKET),
        new NoopMonitor());
  }

  public DDAgentWriter(final DDApi api, final Monitor monitor) {
    this(api, monitor, DISRUPTOR_BUFFER_SIZE, FLUSH_PAYLOAD_DELAY);
  }

  /** Old signature (pre-Monitor) used in tests */
  private DDAgentWriter(final DDApi api) {
    this(api, new NoopMonitor());
  }

  /**
   * Used in the tests.
   *
   * @param api
   * @param disruptorSize Rounded up to next power of 2
   * @param flushFrequencySeconds value < 1 disables scheduled flushes
   */
  private DDAgentWriter(final DDApi api, final int disruptorSize, final int flushFrequencySeconds) {
    this(api, new NoopMonitor(), disruptorSize, flushFrequencySeconds);
  }

  private DDAgentWriter(
      final DDApi api,
      final Monitor monitor,
      final int disruptorSize,
      final int flushFrequencySeconds) {
    this.api = api;
    this.monitor = monitor;

    disruptor =
        new Disruptor<>(
            new TraceEventFactory(),
            Math.max(2, Integer.highestOneBit(disruptorSize - 1) << 1), // Next power of 2
            DISRUPTOR_THREAD_FACTORY,
            ProducerType.MULTI,
            new SleepingWaitStrategy(0, TimeUnit.MILLISECONDS.toNanos(5)));
    disruptor.handleEventsWith(new TraceConsumer());

    apiPhaser = new Phaser(); // Ensure API calls are completed when flushing

    sender = new Sender(flushFrequencySeconds, 5);
    sender.register();
    senderThread = SENDER_THREAD_FACTORY.newThread(sender);
  }

  // Exposing some statistics for consumption by monitors
  public final long getDisruptorCapacity() {
    return disruptor.getRingBuffer().getBufferSize();
  }

  public final long getDisruptorUtilizedCapacity() {
    return getDisruptorCapacity() - getDisruptorRemainingCapacity();
  }

  public final long getDisruptorRemainingCapacity() {
    return disruptor.getRingBuffer().remainingCapacity();
  }

  @Override
  public void write(final List<DDSpan> trace) {
    // We can't add events after shutdown otherwise it will never complete shutting down.
    if (running) {
      final boolean published = disruptor.getRingBuffer().tryPublishEvent(TRANSLATOR, trace);

      if (published) {
        monitor.onPublish(DDAgentWriter.this, trace);
      } else {
        // We're discarding the trace, but we still want to count it.
        droppedCount.incrementAndGet();
        log.debug("Trace written to overfilled buffer. Counted but dropping trace: {}", trace);

        monitor.onFailedPublish(this, trace);
      }
    } else {
      log.debug("Trace written after shutdown. Ignoring trace: {}", trace);

      monitor.onFailedPublish(this, trace);
    }
  }

  @Override
  public void incrementTraceCount() {
    // DQH - What is this for?
  }

  public DDApi getApi() {
    return api;
  }

  @Override
  public void start() {
    disruptor.start();
    senderThread.start();
    running = true;

    monitor.onStart(this);
  }

  @Override
  public void close() {
    running = false;

    // interrupt the sender thread
    senderThread.interrupt();
    try {
      senderThread.join();
    } catch (final InterruptedException e) {
      log.warn("Waiting for flush executor shutdown interrupted.", e);
    }

    disruptor.shutdown();

    monitor.onShutdown(this, false);
  }

  /** This method will block until the flush is complete. */
  public boolean flush() {
    if (!running) {
      return false;
    }

    log.info("Flushing any remaining traces.");
    flushDisruptor();

    // Register with the phaser so we can block until the flush completion.
    apiPhaser.register();
    try {
      // Allow thread to be interrupted.
      apiPhaser.awaitAdvanceInterruptibly(apiPhaser.arriveAndDeregister());

      return true;
    } catch (final InterruptedException e) {
      log.warn("Waiting for flush interrupted.", e);

      return false;
    }
  }

  @Override
  public String toString() {
    // DQH - I don't particularly like the instanceof check,
    // but I decided it was preferable to adding an isNoop method onto
    // Monitor or checking the result of Monitor#toString() to determine
    // if something is *probably* the NoopMonitor.

    String str = "DDAgentWriter { api=" + api;
    if (!(monitor instanceof NoopMonitor)) {
      str += ", monitor=" + monitor;
    }
    str += " }";

    return str;
  }

  private void flushDisruptor() {
    disruptor.publishEvent(FLUSH_TRANSLATOR);

    monitor.onScheduleFlush(this, false);
  }

  private DDApi.Response sendImpl(DDApi.Request apiRequest) {
    try {
      final DDApi.Response apiResponse = api.send(apiRequest);

      if (apiResponse.success()) {
        log.debug("Successfully sent {} traces to the API", apiRequest.numSerializedTraces());
      } else {
        log.debug(
          "Failed to send {} traces (representing {}) of size {} bytes to the API",
          apiRequest.numSerializedTraces(),
          apiRequest.numRepresentedTraces(),
          apiRequest.payloadSize());
      }

      return apiResponse;
    } catch (final Throwable e) {
      log.debug("Failed to send traces to the API: {}", e.getMessage());

      // DQH - 10/2019 - DDApi should wrap most exceptions itself, so this really
      // shouldn't occur.
      // However, just to be safe to start, create a failed Response to handle any
      // spurious Throwable-s.
      return DDApi.Response.failed(e);
    }
  }

  /*
   * This class is not thread-safe.  Disruptor is configured to have a single
   * consumer thread.  The exchange between producer threads & this consumer
   * are handled via volatile fields on the TraceEvent class.
   */
  private class TraceConsumer implements EventHandler<TraceEvent> {
    private DDApi.Request.Builder apiRequestBuilder = new DDApi.Request.Builder();

    @Override
    public void onEvent(
        final TraceEvent traceEvent, final long sequence, final boolean endOfBatch) {

      final boolean shouldFlush = traceEvent.shouldFlush;
      final List<DDSpan> trace = traceEvent.trace;
      traceEvent.reset();

      if (trace != null) {
        apiRequestBuilder.add(trace);
      }
      if (shouldFlush || apiRequestBuilder.payloadSize() >= FLUSH_PAYLOAD_BYTES) {
        prepareRequest();
      }
    }

    private void prepareRequest() {
      if (apiRequestBuilder.numSerializedTraces() == 0) {
        sender.arrive();
        return;
      }

      final int numDropped = droppedCount.getAndSet(0);

      final DDApi.Request apiRequest = apiRequestBuilder
        .addDropped(numDropped)
        .build();
      requestQueue.offer(apiRequest);

      // Initialize with similar size to reduce arraycopy churn.
      apiRequestBuilder = new DDApi.Request.Builder(apiRequest.numSerializedTraces());
    }
  }

  static final class TraceEvent {
    private volatile boolean shouldFlush;
    private volatile List<DDSpan> trace;

    TraceEvent() {
      this.reset();
    }

    void reset() {
      this.shouldFlush = false;
      this.trace = null;
    }
  }

  private static final class TraceEventFactory implements EventFactory<TraceEvent> {
    @Override
    public TraceEvent newInstance() {
      return new TraceEvent();
    }
  }

  /**
   * The Sender is currently run in a single thread, so
   * the mutable fields are not volatile.
   */
  private final class Sender implements Runnable {
    private final int minFlushDelaySecs;
    private final int maxFlushDelaySecs;
    private final int numRetries;

    private int flushDelaySecs;

    public Sender(
      final int flushDelaySecs,
      final int numRetries)
    {
      this.minFlushDelaySecs = flushDelaySecs;
      this.maxFlushDelaySecs = flushDelaySecs << numRetries;
      this.numRetries = numRetries;

      this.flushDelaySecs = this.minFlushDelaySecs;
    }

    void register() {
      apiPhaser.register();
    }

    void arrive() {
      apiPhaser.arrive();
    }

    public final void run() {
      while (true) {
        final DDApi.Request request;

        try {
          request = requestQueue.poll(flushDelaySecs, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
          break;
        }

        if (request == null) {
          // a full delay has passed without a request -- prepare a new request
          flushDisruptor();
          continue;
        }

        try {
          send(request);
        } finally {
          arrive();
        }
      }
    }

    void send(DDApi.Request request) {
      DDApi.Response response = sendImpl(request);

      if (response.success()) {
        monitor.onSend(DDAgentWriter.this, request, false, response);

        decreaseDelay();
        return;
      } else {
        boolean willRetry = (numRetries != 0) && shouldRetry(response);
        monitor.onFailedSend(DDAgentWriter.this, request, false, response, willRetry);

        increaseDelay();

        if (!willRetry) {
          return;
        }
      }

      for (int retry = 1; retry <= numRetries; retry += 1) {
        response = sendImpl(request);

        if (response.success()) {
          monitor.onSend(DDAgentWriter.this, request, true, response);

          decreaseDelay();
          return;
        } else {
          boolean shouldRetry = shouldRetry(response);
          boolean willRetryAgain = (retry < numRetries) && shouldRetry(response);

          monitor.onFailedSend(DDAgentWriter.this, request, true, response, willRetryAgain);

          increaseDelay();

          if (!willRetryAgain) {
            return;
          }
        }
      }
    }

    private boolean shouldRetry(DDApi.Response response) {
      return (response.exception() != null);
    }

    private void increaseDelay() {
      flushDelaySecs = Math.min(maxFlushDelaySecs, flushDelaySecs << 1);
    }

    private void decreaseDelay() {
      flushDelaySecs = Math.max(minFlushDelaySecs, flushDelaySecs >> 1);
    }
  }

  /**
   * Callback interface for monitoring the health of the DDAgentWriter. Provides hooks for major
   * lifecycle events...
   *
   * <ul>
   *   <li>start
   *   <li>shutdown
   *   <li>publishing to disruptor
   *   <li>serializing
   *   <li>sending to agent
   * </ul>
   */
  public interface Monitor {
    void onStart(final DDAgentWriter agentWriter);

    void onShutdown(final DDAgentWriter agentWriter, final boolean flushSuccess);

    void onPublish(final DDAgentWriter agentWriter, final List<DDSpan> trace);

    void onFailedPublish(final DDAgentWriter agentWriter, final List<DDSpan> trace);

    void onScheduleFlush(final DDAgentWriter agentWriter, final boolean previousIncomplete);

    void onSend(
        final DDAgentWriter agentWriter,
        final DDApi.Request request,
        final boolean retrying,
        final DDApi.Response response);

    void onFailedSend(
        final DDAgentWriter agentWriter,
        final DDApi.Request request,
        final boolean retrying,
        final DDApi.Response response,
        final boolean willRetry);
  }

  public static final class NoopMonitor implements Monitor {
    @Override
    public void onStart(final DDAgentWriter agentWriter) {}

    @Override
    public void onShutdown(final DDAgentWriter agentWriter, final boolean flushSuccess) {}

    @Override
    public void onPublish(final DDAgentWriter agentWriter, final List<DDSpan> trace) {}

    @Override
    public void onFailedPublish(final DDAgentWriter agentWriter, final List<DDSpan> trace) {}

    @Override
    public void onScheduleFlush(
        final DDAgentWriter agentWriter, final boolean previousIncomplete) {}

    @Override
    public void onSend(
        final DDAgentWriter agentWriter,
        final DDApi.Request request,
        final boolean retrying,
        final DDApi.Response response) {}

    @Override
    public void onFailedSend(
        final DDAgentWriter agentWriter,
        final DDApi.Request request,
        final boolean retrying,
        final DDApi.Response response,
        final boolean willRetry) {}

    @Override
    public String toString() {
      return "NoOp";
    }
  }

  public static final class StatsDMonitor implements Monitor {
    public static final String PREFIX = "datadog.tracer";

    public static final String LANG_TAG = "lang";
    public static final String LANG_VERSION_TAG = "lang_version";
    public static final String LANG_INTERPRETER_TAG = "lang_interpreter";
    public static final String LANG_INTERPRETER_VENDOR_TAG = "lang_interpreter_vendor";
    public static final String TRACER_VERSION_TAG = "tracer_version";

    private final String hostInfo;
    private final StatsDClient statsd;

    // DQH - Made a conscious choice to not take a Config object here.
    // Letting the creating of the Monitor take the Config,
    // so it can decide which Monitor variant to create.
    public StatsDMonitor(final String host, final int port) {
      hostInfo = host + ":" + port;
      statsd = new NonBlockingStatsDClient(PREFIX, host, port, getDefaultTags());
    }

    // Currently, intended for testing
    private StatsDMonitor(final StatsDClient statsd) {
      hostInfo = null;
      this.statsd = statsd;
    }

    protected static final String[] getDefaultTags() {
      return new String[] {
        tag(LANG_TAG, "java"),
        tag(LANG_VERSION_TAG, DDTraceOTInfo.JAVA_VERSION),
        tag(LANG_INTERPRETER_TAG, DDTraceOTInfo.JAVA_VM_NAME),
        tag(LANG_INTERPRETER_VENDOR_TAG, DDTraceOTInfo.JAVA_VM_VENDOR),
        tag(TRACER_VERSION_TAG, DDTraceOTInfo.VERSION)
      };
    }

    private static final String tag(final String tagPrefix, final String tagValue) {
      return tagPrefix + ":" + tagValue;
    }

    @Override
    public void onStart(final DDAgentWriter agentWriter) {
      // DQH - Also resent onSend, so it stays present in the UIx`
      statsd.recordGaugeValue("queue.max_length", agentWriter.getDisruptorCapacity());
    }

    @Override
    public void onShutdown(final DDAgentWriter agentWriter, final boolean flushSuccess) {}

    @Override
    public void onPublish(final DDAgentWriter agentWriter, final List<DDSpan> trace) {
      statsd.count("queue.accepted", 1);
      statsd.count("queue.dropped", 0);
      statsd.count("queue.accepted_lengths", trace.size());
    }

    @Override
    public void onFailedPublish(final DDAgentWriter agentWriter, final List<DDSpan> trace) {
      statsd.count("queue.accepted", 0);
      statsd.count("queue.dropped", 1);
      statsd.count("queue.accepted_lengths", trace.size());
    }

    @Override
    public void onScheduleFlush(final DDAgentWriter agentWriter, final boolean previousIncomplete) {
      // not recorded
    }

    @Override
    public void onSend(
        final DDAgentWriter agentWriter,
        final DDApi.Request request,
        final boolean retrying,
        final DDApi.Response response) {
      onSendAttempt(agentWriter, request, retrying, response);

      if (retrying) {
        statsd.count("api.recovered.traces", request.numSerializedTraces());
        statsd.count("api.recovered.spans", request.numSerializedSpans());

        statsd.count("api.lost.traces", 0);
        statsd.count("api.lost.spans", 0);
      }

      statsd.recordGaugeValue("queue.max_length", agentWriter.getDisruptorCapacity());
    }

    @Override
    public void onFailedSend(
        final DDAgentWriter agentWriter,
        final DDApi.Request request,
        final boolean retrying,
        final DDApi.Response response,
        final boolean willRetry) {
      onSendAttempt(agentWriter, request, retrying, response);

      if (!willRetry) {
        statsd.count("api.lost.traces", request.numSerializedTraces());
        statsd.count("api.lost.spans", request.numSerializedSpans());
      }
    }

    private void onSendAttempt(
        final DDAgentWriter agentWriter,
        final DDApi.Request request,
        final boolean retrying,
        final DDApi.Response response) {
      statsd.incrementCounter("api.requests");
      if (retrying) {
        statsd.count("api.retries", 1);
      } else {
        statsd.count("api.retries", 0);
      }

      statsd.recordGaugeValue("queue.length", request.numSerializedSpans());
      statsd.recordGaugeValue("queue.size", request.payloadSize());

      if (response.exception() != null) {
        // covers communication errors -- both not receiving a response or
        // receiving malformed response (even when otherwise successful)
        statsd.count("api.errors", 1);
      } else {
        statsd.count("api.errors", 0);
      }

      if (response.status() != null) {
        statsd.incrementCounter("api.responses", "status: " + response.status());
      }
    }

    public String toString() {
      if (hostInfo == null) {
        return "StatsD";
      } else {
        return "StatsD { host=" + hostInfo + " }";
      }
    }
  }
}
