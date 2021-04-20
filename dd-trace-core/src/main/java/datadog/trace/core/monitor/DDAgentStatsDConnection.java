package datadog.trace.core.monitor;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.timgroup.statsd.NoOpStatsDClient;
import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClientErrorHandler;
import datadog.trace.api.Config;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DDAgentStatsDConnection implements StatsDClientErrorHandler {
  private static final Logger log = LoggerFactory.getLogger(DDAgentStatsDConnection.class);

  private static final com.timgroup.statsd.StatsDClient NO_OP = new NoOpStatsDClient();

  private final String host;
  private final int port;

  private final AtomicInteger clientCount = new AtomicInteger(0);
  private final AtomicInteger errorCount = new AtomicInteger(0);
  volatile com.timgroup.statsd.StatsDClient statsd = NO_OP;

  DDAgentStatsDConnection(final String host, final int port) {
    this.host = host;
    this.port = port;
  }

  @Override
  public void handle(final Exception e) {
    errorCount.incrementAndGet();
    log.error(
        "{} in StatsD client - {}", e.getClass().getSimpleName(), statsDAddress(host, port), e);
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
            "Scheduling StatsD connection in {} seconds - {}",
            remainingDelay,
            statsDAddress(host, port));
      }
      AgentTaskScheduler.INSTANCE.scheduleWithJitter(
          ConnectTask.INSTANCE, this, remainingDelay, SECONDS);
    } else {
      doConnect();
    }
  }

  private void doConnect() {
    synchronized (this) {
      if (NO_OP == statsd && clientCount.get() > 0) {
        if (log.isDebugEnabled()) {
          log.debug("Creating StatsD client - {}", statsDAddress(host, port));
        }
        // when using UDS, set "entity-id" to "none" to avoid having the DogStatsD
        // server add origin tags (see https://github.com/DataDog/jmxfetch/pull/264)
        String entityID = port == 0 ? "none" : null;
        try {
          statsd =
              new NonBlockingStatsDClient(
                  null, host, port, Integer.MAX_VALUE, null, this, entityID);
        } catch (final Exception e) {
          log.error("Unable to create StatsD client - {}", statsDAddress(host, port), e);
        }
      }
    }
  }

  private void doClose() {
    synchronized (this) {
      if (NO_OP != statsd && 0 == clientCount.get()) {
        if (log.isDebugEnabled()) {
          log.debug("Closing StatsD client - {}", statsDAddress(host, port));
        }
        try {
          statsd.close();
        } catch (final Exception e) {
          log.debug("Problem closing StatsD client - {}", statsDAddress(host, port), e);
        } finally {
          statsd = NO_OP;
        }
      }
    }
  }

  static String statsDAddress(final String host, final int port) {
    return port > 0 ? host + ':' + port : host;
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
