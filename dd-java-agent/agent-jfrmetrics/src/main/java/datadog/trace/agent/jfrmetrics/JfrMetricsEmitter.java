package datadog.trace.agent.jfrmetrics;

import datadog.trace.api.Config;
import datadog.trace.api.StatsDClient;
import datadog.trace.api.StatsDClientManager;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.consumer.RecordingStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Used from datadog.trace.bootstrap.Agent
public final class JfrMetricsEmitter {
  private static final Logger log = LoggerFactory.getLogger(JfrMetricsEmitter.class);
  static final String JVM_RESIDENT_SET_SIZE_KEY = "jvm.resident_set_size";
  static final String JVM_NATIVE_MEMORY_COMMITTED_KEY = "jvm.native_memory_committed";
  static final String JVM_NATIVE_MEMORY_RESERVED_KEY = "jvm.native_memory_reserved";
  static final String JVM_VIRTUAL_THREAD_PINNED_KEY = "jvm.virtual_thread_pinned";
  static final String JVM_VIRTUAL_THREAD_SUBMIT_FAILED_KEY = "jvm.virtual_thread_submit_failed";

  protected static final class Triggering {
    private final long threshold;
    private final AtomicLong lastTs = new AtomicLong(-1);

    Triggering(Duration threshold) {
      this.threshold = threshold.toMillis();
    }

    void record(Runnable callback) {
      long now = System.currentTimeMillis();
      lastTs.updateAndGet(
          ts -> {
            if (ts == -1 || now - ts > threshold) {
              callback.run();
              return now;
            }
            return ts;
          });
    }
  }

  private static JfrMetricsEmitter instance;

  public static synchronized void run(StatsDClientManager statsdManager) {
    if (instance != null) {
      return;
    }

    Config cfg = Config.get();

    String host = cfg.getJmxFetchStatsdHost();
    Integer port = cfg.getJmxFetchStatsdPort();
    String namedPipe = cfg.getDogStatsDNamedPipe();

    instance =
        new JfrMetricsEmitter(
            statsdManager.statsDClient(host, port, namedPipe, null, null),
            ConfigProvider.getInstance());
    Thread t = new Thread(instance::run, "JfrMetricsEmitter");
    t.start();
  }

  private final StatsDClient statsd;

  private final boolean enabled;
  private final int periodSeconds;

  private final Triggering vtSubmitFailedTrigger;
  private final Triggering vtPinnedTs;

  JfrMetricsEmitter(StatsDClient statsd, ConfigProvider configProvider) {
    enabled =
        configProvider.getBoolean(
            JfrMetricsConfig.JFR_METRICS_ENABLED, JfrMetricsConfig.JFR_METRICS_ENABLED_DEFAULT);
    periodSeconds =
        configProvider.getInteger(
            JfrMetricsConfig.JFR_METRICS_PERIOD_SECONDS,
            JfrMetricsConfig.JFR_METRICS_PERIOD_SECONDS_DEFAULT);
    this.statsd = statsd;
    Duration threshold =
        Duration.ofSeconds(
            configProvider.getInteger(
                JfrMetricsConfig.JFR_METRICS_EVENT_THRESHOLD_SECONDS,
                JfrMetricsConfig.JFR_METRICS_EVENT_THRESHOLD_SECONDS_DEFAULT));
    vtSubmitFailedTrigger = new Triggering(threshold);
    vtPinnedTs = new Triggering(threshold);
  };

  void runAsync() {
    Thread t = new Thread(this::run, "JfrMetricsEmitter");
    t.setDaemon(true);
    t.start();
  }

  public void run() {
    if (!enabled) {
      log.info("JFR metrics collection is disabled");
      return;
    }
    try (var rs = new RecordingStream()) {
      rs.enable("jdk.VirtualThreadSubmitFailed");
      rs.enable("jdk.VirtualThreadPinned");
      rs.enable("jdk.ResidentSetSize").withPeriod(Duration.ofSeconds(periodSeconds));
      rs.enable("jdk.NativeMemoryUsageTotal").withPeriod(Duration.ofSeconds(periodSeconds));

      rs.onEvent(
          "jdk.VirtualThreadSubmitFailed",
          event -> {
            statsd.incrementCounter(JVM_VIRTUAL_THREAD_SUBMIT_FAILED_KEY);
            vtSubmitFailedTrigger.record(
                () -> {
                  statsd.event(
                      "Virtual Thread Submit Failed",
                      "Virtual Thread "
                          + event.getThread().getJavaName()
                          + "( + "
                          + event.getThread().getId()
                          + ") submit failed.",
                      StatsDClient.EventKind.ERROR,
                      "thread:" + event.getThread().getJavaName(),
                      "runtime-id:" + Config.get().getRuntimeId());
                });
          });
      rs.onEvent(
          "jdk.VirtualThreadPinned",
          event -> {
            statsd.distribution(JVM_VIRTUAL_THREAD_PINNED_KEY, event.getDuration().toMillis());
            vtPinnedTs.record(
                () -> {
                  statsd.event(
                      "Virtual Thread Pinned",
                      "Virtual Thread "
                          + event.getThread().getJavaName()
                          + "( + "
                          + event.getThread().getId()
                          + ") was pinned.",
                      StatsDClient.EventKind.WARNING,
                      "thread:" + event.getThread().getJavaName(),
                      "runtime-id:" + Config.get().getRuntimeId());
                });
          });

      rs.onEvent(
          "jdk.ResidentSetSize",
          event -> {
            statsd.gauge(JVM_RESIDENT_SET_SIZE_KEY, event.getLong("size"));
          });

      rs.onEvent(
          "jdk.NativeMemoryUsageTotal",
          event -> {
            statsd.gauge(JVM_NATIVE_MEMORY_COMMITTED_KEY, event.getLong("committed"));
            statsd.gauge(JVM_NATIVE_MEMORY_RESERVED_KEY, event.getLong("reserved"));
          });

      rs.start();
    }
  }
}
