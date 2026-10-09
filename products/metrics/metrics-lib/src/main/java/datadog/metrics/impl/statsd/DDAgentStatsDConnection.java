package datadog.metrics.impl.statsd;

import static datadog.trace.api.ConfigDefaults.DEFAULT_DOGSTATSD_SOCKET_PATH;
import static datadog.trace.util.AgentThreadFactory.AgentThread.STATSD_CLIENT;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.timgroup.statsd.NoOpDirectStatsDClient;
import com.timgroup.statsd.NonBlockingStatsDClientBuilder;
import com.timgroup.statsd.StatsDClientErrorHandler;
import datadog.common.filesystem.Files;
import datadog.environment.OperatingSystem;
import datadog.logging.IOLogger;
import datadog.logging.RatelimitedLogger;
import datadog.trace.api.Config;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.AgentThreadFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DDAgentStatsDConnection implements StatsDClientErrorHandler {
  private static final Logger log = LoggerFactory.getLogger(DDAgentStatsDConnection.class);
  private static final IOLogger ioLogger = new IOLogger(log);
  private static final RatelimitedLogger recreateLogger = new RatelimitedLogger(log, 5, MINUTES);

  private static final com.timgroup.statsd.StatsDClient NO_OP = new NoOpDirectStatsDClient();

  private static final String UNIX_DOMAIN_SOCKET_PREFIX = "unix://";

  private static final AgentThreadFactory STATSD_CLIENT_THREAD_FACTORY =
      new AgentThreadFactory(STATSD_CLIENT);

  private static final long RETRY_DELAY = 10;
  private static final long MAX_RETRY_DELAY = 60;

  private boolean usingDefaultPort;
  private volatile String host;
  private volatile Integer port;
  private final String namedPipe;
  private final boolean useAggregation;

  private final AtomicInteger clientCount = new AtomicInteger(0);
  private final AtomicInteger errorCount = new AtomicInteger(0);
  private final AtomicInteger retries = new AtomicInteger(0);
  private final WriteFailureStreak writeFailureStreak = new WriteFailureStreak();

  volatile com.timgroup.statsd.StatsDClient statsd = NO_OP;

  DDAgentStatsDConnection(
      final String host, final Integer port, final String namedPipe, boolean useAggregation) {
    this.host = host;
    this.port = port;
    this.namedPipe = namedPipe;
    this.useAggregation = useAggregation;
  }

  @Override
  public void handle(final Exception e) {
    errorCount.incrementAndGet();
    String message = e.getClass().getSimpleName() + " in StatsD client - " + statsDAddress();
    ioLogger.error(message, e);
    if (writeFailureStreak.recordError(NANOSECONDS.toMillis(System.nanoTime()))) {
      // handle() runs on a client thread, so close the client from another thread
      AgentTaskScheduler.get().schedule(RecreateTask.INSTANCE, this, 0, MILLISECONDS);
    }
  }

  public void acquire() {
    if (clientCount.getAndIncrement() == 0) {
      scheduleConnect();
    }
  }

  public void release() {
    if (clientCount.decrementAndGet() == 0) {
      doClose();
    }
  }

  public int getErrorCount() {
    return errorCount.get();
  }

  private void scheduleConnect() {
    long remainingDelay =
        Config.get().getDogStatsDStartDelay()
            - MILLISECONDS.toSeconds(
                System.currentTimeMillis() - Config.get().getStartTimeMillis());

    if (remainingDelay > 0) {
      if (log.isDebugEnabled()) {
        log.debug(
            "Scheduling StatsD connection in {} seconds - {}", remainingDelay, statsDAddress());
      }
      AgentTaskScheduler.get()
          .scheduleWithJitter(ConnectTask.INSTANCE, this, remainingDelay, SECONDS);
    } else {
      doConnect();
    }
  }

  private void doConnect() {
    synchronized (this) {
      if (NO_OP == statsd && clientCount.get() > 0) {
        discoverConnectionSettings();
        if (log.isDebugEnabled()) {
          log.debug("Creating StatsD client - {}", statsDAddress());
        }

        NonBlockingStatsDClientBuilder clientBuilder =
            new NonBlockingStatsDClientBuilder()
                .threadFactory(STATSD_CLIENT_THREAD_FACTORY)
                .enableTelemetry(false)
                .enableAggregation(useAggregation)
                .hostname(host)
                .port(port)
                .namedPipe(namedPipe)
                .errorHandler(this);

        // when using UDS, set "entity-id" to "none" to avoid having the DogStatsD
        // server add origin tags (see https://github.com/DataDog/jmxfetch/pull/264)
        if (this.port == 0) {
          clientBuilder.entityID("none");
        } else {
          clientBuilder.entityID(null);
        }

        Integer queueSize = Config.get().getStatsDClientQueueSize();
        if (queueSize != null) {
          clientBuilder.queueSize(queueSize);
        }

        // when using UDS set the datagram size to 8k (2k on Mac due to lower OS default)
        // but also make sure packet size isn't larger than the configured socket buffer
        if (this.port == 0) {
          Integer timeout = Config.get().getStatsDClientSocketTimeout();
          if (timeout != null) {
            clientBuilder.timeout(timeout);
          }
          Integer bufferSize = Config.get().getStatsDClientSocketBuffer();
          if (bufferSize != null) {
            clientBuilder.socketBufferSize(bufferSize);
          }
          int packetSize = OperatingSystem.isMacOs() ? 2048 : 8192;
          if (bufferSize != null && bufferSize < packetSize) {
            packetSize = bufferSize;
          }
          clientBuilder.maxPacketSizeBytes(packetSize);
        }

        if (log.isDebugEnabled()) {
          if (this.port == 0) {
            log.debug(
                "Configured StatsD client - queueSize={}, maxPacketSize={}, socketBuffer={}, socketTimeout={}",
                clientBuilder.queueSize,
                clientBuilder.maxPacketSizeBytes,
                clientBuilder.socketBufferSize,
                clientBuilder.timeout);
          } else {
            log.debug("Configured StatsD client - queueSize={}", clientBuilder.queueSize);
          }
        }

        try {
          statsd = clientBuilder.build();
          int failedAttempts = retries.getAndSet(0);
          if (failedAttempts > 0) {
            log.info(
                "StatsD connected to {} after {} failed attempts", statsDAddress(), failedAttempts);
          } else if (log.isDebugEnabled()) {
            log.debug("StatsD connected to {}", statsDAddress());
          }
        } catch (final Exception e) {
          int failedAttempts = retries.incrementAndGet();
          long delaySeconds = connectRetryDelaySeconds(failedAttempts);
          if (failedAttempts == 1) {
            log.warn(
                "Unable to create StatsD client - {} - Will keep retrying, at least every {} seconds: {}",
                statsDAddress(),
                MAX_RETRY_DELAY,
                e.getMessage());
          } else if (log.isDebugEnabled()) {
            log.debug(
                "Scheduling StatsD connection in {} seconds - {}", delaySeconds, statsDAddress());
          }
          // no jitter, so the delay stays within MAX_RETRY_DELAY
          AgentTaskScheduler.get().schedule(ConnectTask.INSTANCE, this, delaySeconds, SECONDS);
        } catch (Throwable t) {
          if (log.isDebugEnabled()) {
            // Display full stack traces on debug logs
            log.warn("Unable to create StatsD client - {} - Will not retry", statsDAddress(), t);
          } else {
            Throwable rootCause = t;
            int i = 100; // arbitrary limit to avoid infinite loops with cycling causes
            do {
              rootCause = rootCause.getCause();
              i--;
            } while (rootCause.getCause() != null && i > 0);
            log.warn(
                "Unable to create StatsD client - {} - Will not retry: {}, {}",
                statsDAddress(),
                t.getMessage(),
                rootCause.getMessage());
          }
        }
      }
    }
  }

  /**
   * Delay before the next connection attempt: doubles from {@link #RETRY_DELAY} up to {@link
   * #MAX_RETRY_DELAY} seconds. Attempts never stop, so a DogStatsD that comes up late is still
   * picked up.
   */
  static long connectRetryDelaySeconds(int failedAttempts) {
    long delaySeconds = RETRY_DELAY;
    for (int i = 1; i < failedAttempts && delaySeconds < MAX_RETRY_DELAY; i++) {
      delaySeconds *= 2;
    }
    return Math.min(delaySeconds, MAX_RETRY_DELAY);
  }

  @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
  private void discoverConnectionSettings() {
    if (namedPipe != null) {
      return;
    }

    if (null == host) {
      if (!OperatingSystem.isWindows() && Files.exists(new File(DEFAULT_DOGSTATSD_SOCKET_PATH))) {
        log.info("Detected {}. Using it to send StatsD data.", DEFAULT_DOGSTATSD_SOCKET_PATH);
        host = DEFAULT_DOGSTATSD_SOCKET_PATH;
        port = 0; // tells dogstatsd client to treat host as a socket path
      } else {
        host = Config.get().getAgentHost();
      }
    }

    if (host.startsWith(UNIX_DOMAIN_SOCKET_PREFIX)) {
      host = host.substring(UNIX_DOMAIN_SOCKET_PREFIX.length());
      port = 0; // tells dogstatsd client to treat host as a socket path
    }
    if (null == port) {
      port = DDAgentStatsDClientManager.getDefaultStatsDPort();
      usingDefaultPort = true;
    }
  }

  void handleDefaultPortChange(final int newPort) {
    synchronized (this) {
      if (NO_OP != statsd && usingDefaultPort && newPort != port) {
        if (log.isDebugEnabled()) {
          log.debug("Closing StatsD client - {}", statsDAddress());
        }
        try {
          statsd.close();
        } finally {
          statsd = NO_OP;
          port = null; // clear so it will pickup latest default
          doConnect();
        }
      }
    }
  }

  /**
   * The client never reopens its named pipe or socket, so after the DogStatsD it was writing to
   * goes away, every write fails even once a new DogStatsD serves the same name.
   */
  private void recreateClient() {
    synchronized (this) {
      if (NO_OP != statsd && clientCount.get() > 0) {
        recreateLogger.warn(
            "StatsD client writes to {} have kept failing for at least {} seconds; recreating the client",
            statsDAddress(),
            MILLISECONDS.toSeconds(WriteFailureStreak.RECREATE_AFTER_MILLIS));
        try {
          statsd.close();
        } catch (final Exception e) {
          log.debug("Problem closing StatsD client - {}", statsDAddress(), e);
        } finally {
          statsd = NO_OP;
        }
        doConnect();
      }
    }
  }

  private void doClose() {
    synchronized (this) {
      if (NO_OP != statsd && 0 == clientCount.get()) {
        if (log.isDebugEnabled()) {
          log.debug("Closing StatsD client - {}", statsDAddress());
        }
        try {
          statsd.close();
        } catch (final Exception e) {
          log.debug("Problem closing StatsD client - {}", statsDAddress(), e);
        } finally {
          statsd = NO_OP;
        }
      }
    }
  }

  private String statsDAddress() {
    if (namedPipe != null) {
      return namedPipe;
    }

    return (null != host ? host : "<auto-detect>") + (null != port && port > 0 ? ":" + port : "");
  }

  /**
   * Tracks how long StatsD writes have kept failing. Errors more than {@link #MAX_ERROR_GAP_MILLIS}
   * apart start a new streak, and a streak needs {@link #MIN_ERRORS} errors spanning {@link
   * #RECREATE_AFTER_MILLIS}, so brief or isolated timeouts under load do not recreate the client.
   */
  static final class WriteFailureStreak {
    static final long RECREATE_AFTER_MILLIS = 30_000;
    // longer than the 30 second tracer health metrics flush, so a dead pipe keeps one streak going
    static final long MAX_ERROR_GAP_MILLIS = 60_000;
    static final int MIN_ERRORS = 3;

    private int errors;
    private long firstErrorMillis;
    private long lastErrorMillis;

    /**
     * @return true when the client should be recreated; the streak then starts over
     */
    synchronized boolean recordError(long nowMillis) {
      if (errors == 0 || nowMillis - lastErrorMillis > MAX_ERROR_GAP_MILLIS) {
        errors = 0;
        firstErrorMillis = nowMillis;
      }
      errors++;
      lastErrorMillis = nowMillis;
      if (errors >= MIN_ERRORS && nowMillis - firstErrorMillis >= RECREATE_AFTER_MILLIS) {
        errors = 0;
        return true;
      }
      return false;
    }
  }

  private static final class RecreateTask
      implements AgentTaskScheduler.Task<DDAgentStatsDConnection> {
    public static final RecreateTask INSTANCE = new RecreateTask();

    @Override
    public void run(final DDAgentStatsDConnection target) {
      target.recreateClient();
    }
  }

  private static final class ConnectTask
      implements AgentTaskScheduler.Task<DDAgentStatsDConnection> {
    public static final ConnectTask INSTANCE = new ConnectTask();

    @Override
    public void run(final DDAgentStatsDConnection target) {
      target.doConnect();
    }
  }
}
