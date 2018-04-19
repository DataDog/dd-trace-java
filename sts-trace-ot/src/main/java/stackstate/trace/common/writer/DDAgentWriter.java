package stackstate.trace.common.writer;

import com.google.auto.service.AutoService;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import stackstate.opentracing.DDSpan;
import stackstate.trace.common.Service;

/**
 * This writer write provided traces to the a DD agent which is most of time located on the same
 * host.
 *
 * <p>
 *
 * <p>It handles writes asynchronuously so the calling threads are automatically released. However,
 * if too much spans are collected the writers can reach a state where it is forced to drop incoming
 * spans.
 */
@Slf4j
@AutoService(Writer.class)
public class DDAgentWriter implements Writer {

  /** Default location of the DD agent */
  public static final String DEFAULT_HOSTNAME = "localhost";

  public static final int DEFAULT_PORT = 8126;

  /** Maximum number of traces kept in memory */
  static final int DEFAULT_MAX_TRACES = 1000;

  /** Timeout for the API in seconds */
  static final long API_TIMEOUT_SECONDS = 1;

  /** Flush interval for the API in seconds */
  static final long FLUSH_TIME_SECONDS = 1;

  private final ThreadFactory agentWriterThreadFactory =
      new ThreadFactoryBuilder().setNameFormat("dd-agent-writer-%d").setDaemon(true).build();

  /** Scheduled thread pool, acting like a cron */
  private final ScheduledExecutorService scheduledExecutor =
      Executors.newScheduledThreadPool(1, agentWriterThreadFactory);

  /** Effective thread pool, where real logic is done */
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(agentWriterThreadFactory);

  /** The DD agent api */
  private final DDApi api;

  /** In memory collection of traces waiting for departure */
  private final WriterQueue<List<DDSpan>> traces;

  private boolean queueFullReported = false;

  public DDAgentWriter() {
    this(new DDApi(DEFAULT_HOSTNAME, DEFAULT_PORT));
  }

  public DDAgentWriter(final DDApi api) {
    this(api, new WriterQueue<List<DDSpan>>(DEFAULT_MAX_TRACES));
  }

  public DDAgentWriter(final DDApi api, final WriterQueue<List<DDSpan>> queue) {
    super();
    this.api = api;
    traces = queue;
  }

  /* (non-Javadoc)
   * @see datadog.trace.Writer#write(java.util.List)
   */
  @Override
  public void write(final List<DDSpan> trace) {
    final List<DDSpan> removed = traces.add(trace);
    if (removed != null && !queueFullReported) {
      log.debug("Queue is full, traces will be discarded, queue size: {}", DEFAULT_MAX_TRACES);
      queueFullReported = true;
      return;
    }
    queueFullReported = false;
  }

  /* (non-Javadoc)
   * @see datadog.trace.Writer#writeServices(java.util.List)
   */
  @Override
  public void writeServices(final Map<String, Service> services) {

    final Runnable task =
        new Runnable() {
          @Override
          public void run() {
            // SEND the payload to the agent
            log.debug("Async writer about to write {} services", services.size());
            if (api.sendServices(services)) {
              log.debug("Async writer just sent  {} services", services.size());
            } else {
              log.warn("Failed for Async writer to send {} services", services.size());
            }
          }
        };
    executor.submit(task);
  }

  /* (non-Javadoc)
   * @see Writer#start()
   */
  @Override
  public void start() {
    scheduledExecutor.scheduleAtFixedRate(
        new TracesSendingTask(), 0, FLUSH_TIME_SECONDS, TimeUnit.SECONDS);
  }

  /* (non-Javadoc)
   * @see datadog.trace.Writer#close()
   */
  @Override
  public void close() {
    scheduledExecutor.shutdownNow();
    executor.shutdownNow();
    try {
      scheduledExecutor.awaitTermination(500, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException e) {
      log.info("Writer properly closed and async writer interrupted.");
    }

    try {
      executor.awaitTermination(500, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException e) {
      log.info("Writer properly closed and async writer interrupted.");
    }
  }

  @Override
  public String toString() {
    return "DDAgentWriter { api=" + api + " }";
  }

  public DDApi getApi() {
    return api;
  }

  /** Infinite tasks blocking until some spans come in the blocking queue. */
  class TracesSendingTask implements Runnable {

    @Override
    public void run() {
      final Future<Long> future = executor.submit(new SendingTask());
      try {
        final long nbTraces = future.get(API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (nbTraces > 0) {
          log.debug("Successfully sent {} traces to the API", nbTraces);
        }
      } catch (final TimeoutException e) {
        log.debug("Timeout! Failed to send traces to the API: {}", e.getMessage());
      } catch (final Throwable e) {
        log.debug("Failed to send traces to the API: {}", e.getMessage());
      }
    }

    class SendingTask implements Callable<Long> {

      @Override
      public Long call() throws Exception {
        if (traces.isEmpty()) {
          return 0L;
        }

        final List<List<DDSpan>> payload = traces.getAll();

        if (log.isDebugEnabled()) {
          int nbSpans = 0;
          for (final List<?> trace : payload) {
            nbSpans += trace.size();
          }

          log.debug("Sending {} traces ({} spans) to the API (async)", payload.size(), nbSpans);
        }
        final boolean isSent = api.sendTraces(payload);

        if (!isSent) {
          log.debug("Failing to send {} traces to the API", payload.size());
          return 0L;
        }
        return (long) payload.size();
      }
    }
  }
}
