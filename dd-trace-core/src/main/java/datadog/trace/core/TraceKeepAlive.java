package datadog.trace.core;

import datadog.trace.api.config.TracerConfig;
import datadog.trace.core.util.Clock;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A monitor thread scheduled on a regular basis. It triggers keep-alive spans to be sent for
 * long-running traces.
 */
public class TraceKeepAlive implements AgentTaskScheduler.Task<Boolean> {
  private static final Logger LOGGER = LoggerFactory.getLogger(TraceKeepAlive.class);
  public static final long MAX_SPAN_AGE_NANOS = TimeUnit.HOURS.toNanos(12);

  private final Object dummy = new Object();
  private final ConcurrentMap<PendingTrace, Object> pendingTraces = new ConcurrentHashMap<>();
  private final long keepAlivePeriod;

  private volatile AgentTaskScheduler.Scheduled<Boolean> scheduled;

  public TraceKeepAlive(final long keepAlivePeriodMillis) {
    if (keepAlivePeriodMillis <= 0) {
      throw new IllegalArgumentException(
          TracerConfig.LONG_RUNNING_TRACE_FLUSH_INTERVAL + " property should be strictly positive");
    }
    this.keepAlivePeriod = TimeUnit.MILLISECONDS.toNanos(keepAlivePeriodMillis);
  }

  @Override
  public void run(Boolean ignored) {
    final long now = Clock.currentNanoTime();
    for (final PendingTrace pt : pendingTraces.keySet()) {
      pt.keepAliveUnfinished(now, keepAlivePeriod);
    }
  }

  public void start() {
    synchronized (this) {
      if (scheduled == null) {
        LOGGER.debug(
            "Starting long running keepalive monitor. It will flush pending thread each {} millis",
            keepAlivePeriod / 1e6);
        scheduled =
            AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
                this, true, 0L, keepAlivePeriod, TimeUnit.NANOSECONDS);
      }
    }
  }

  public void stop() {
    synchronized (this) {
      if (scheduled != null) {
        scheduled.cancel();
        scheduled = null;
      }
    }
    LOGGER.debug("Long running keepalive monitor stopped");
  }

  public void onPendingTraceBegins(final PendingTrace pendingTrace) {
    pendingTraces.put(pendingTrace, dummy);
  }

  public void onPendingTraceEnds(final PendingTrace pendingTrace) {
    pendingTraces.remove(pendingTrace);
  }
}
