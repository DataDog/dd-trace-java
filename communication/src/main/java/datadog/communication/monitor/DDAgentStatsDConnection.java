package datadog.communication.monitor;

import static datadog.trace.api.ConfigDefaults.DEFAULT_DOGSTATSD_SOCKET_PATH;
import static datadog.trace.util.AgentThreadFactory.AgentThread.STATSD_CLIENT;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.timgroup.statsd.NoOpDirectStatsDClient;
import com.timgroup.statsd.NonBlockingStatsDClientBuilder;
import com.timgroup.statsd.StatsDClientErrorHandler;
import datadog.common.filesystem.Files;
import datadog.environment.OperatingSystem;
import datadog.trace.api.Config;
import datadog.trace.relocate.api.IOLogger;
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

  private static final com.timgroup.statsd.StatsDClient NO_OP = new NoOpDirectStatsDClient();

  private static final String UNIX_DOMAIN_SOCKET_PREFIX = "unix://";

  private static final AgentThreadFactory STATSD_CLIENT_THREAD_FACTORY =
      new AgentThreadFactory(STATSD_CLIENT);

  private static final int RETRY_DELAY = 10;
  private static final int MAX_RETRIES = 20;

  private boolean usingDefaultPort;
  private volatile String host;
  private volatile Integer port;
  private final String namedPipe;
  private final boolean useAggregation;

  private final AtomicInteger clientCount = new AtomicInteger(0);
  private final AtomicInteger errorCount = new AtomicInteger(0);
  private final AtomicInteger retries = new AtomicInteger(0);

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
          if (log.isDebugEnabled()) {
            log.debug("StatsD connected to {}", statsDAddress());
          }
        } catch (final Exception e) {
          if (retries.getAndIncrement() < MAX_RETRIES) {
            if (log.isDebugEnabled()) {
              log.debug(
                  "Scheduling StatsD connection in {} seconds - {}", RETRY_DELAY, statsDAddress());
            }
            AgentTaskScheduler.get()
                .scheduleWithJitter(ConnectTask.INSTANCE, this, RETRY_DELAY, SECONDS);
          } else {
            log.debug("Max retries have been reached. Will not attempt again.");
          }
        } catch (Throwable t) {
          log.error("Unable to create StatsD client - {} - Will not retry", statsDAddress(), t);
        }
      }
    }
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

  private static final class ConnectTask
      implements AgentTaskScheduler.Task<DDAgentStatsDConnection> {
    public static final ConnectTask INSTANCE = new ConnectTask();

    @Override
    public void run(final DDAgentStatsDConnection target) {
      target.doConnect();
    }
  }
}
