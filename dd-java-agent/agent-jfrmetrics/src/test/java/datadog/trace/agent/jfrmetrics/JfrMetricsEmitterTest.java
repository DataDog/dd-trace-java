package datadog.trace.agent.jfrmetrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.trace.api.StatsDClient;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

public class JfrMetricsEmitterTest {
  private static class TestMetricSupport implements StatsDClient {
    private final Map<String, Long> counterMap = new HashMap<>();
    private final Map<String, Double> gaugeMap = new HashMap<>();
    private final Set<String> metricNames = new HashSet<>();
    private final Set<String> eventNames = new HashSet<>();

    @Override
    public void incrementCounter(String metricName, String... tags) {
      counterMap.compute(metricName, (k, v) -> v == null ? 1 : v + 1);
      metricNames.add(metricName);
    }

    @Override
    public void count(String metricName, long delta, String... tags) {
      counterMap.compute(metricName, (k, v) -> v == null ? 1 : v + delta);
      metricNames.add(metricName);
    }

    @Override
    public void gauge(String metricName, double value, String... tags) {
      gaugeMap.put(metricName, value);
      metricNames.add(metricName);
    }

    @Override
    public void gauge(String metricName, long value, String... tags) {
      gaugeMap.put(metricName, (double) value);
      metricNames.add(metricName);
    }

    @Override
    public void histogram(String metricName, long value, String... tags) {
      metricNames.add(metricName);
    }

    @Override
    public void histogram(String metricName, double value, String... tags) {
      metricNames.add(metricName);
    }

    @Override
    public void distribution(String metricName, long value, String... tags) {
      metricNames.add(metricName);
    }

    @Override
    public void distribution(String metricName, double value, String... tags) {
      metricNames.add(metricName);
    }

    @Override
    public void serviceCheck(
        String serviceCheckName, String status, String message, String... tags) {}

    @Override
    public void event(String title, String message, EventKind kind, String... tags) {
      eventNames.add(title);
    }

    @Override
    public void error(Exception error) {}

    @Override
    public int getErrorCount() {
      return 0;
    }

    @Override
    public void close() {}
  }

  @Test
  void testRss() throws Exception {
    Properties override = new Properties();
    override.setProperty(JfrMetricsConfig.JFR_METRICS_ENABLED, "true");
    override.setProperty(JfrMetricsConfig.JFR_METRICS_PERIOD_SECONDS, "1");

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(override);

    TestMetricSupport metricSupport = new TestMetricSupport();
    JfrMetricsEmitter emitter = new JfrMetricsEmitter(metricSupport, configProvider);

    emitter.runAsync();
    waitFull(1_500);

    assertTrue(metricSupport.gaugeMap.containsKey(JfrMetricsEmitter.JVM_RESIDENT_SET_SIZE_KEY));
  }

  @Test
  void testVirtualThreads() throws Exception {
    // set up the metrics emitter
    Properties override = new Properties();
    override.setProperty(JfrMetricsConfig.JFR_METRICS_ENABLED, "true");
    override.setProperty(JfrMetricsConfig.JFR_METRICS_PERIOD_SECONDS, "1");

    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(override);

    TestMetricSupport metricSupport = new TestMetricSupport();
    JfrMetricsEmitter emitter = new JfrMetricsEmitter(metricSupport, configProvider);

    emitter.runAsync();

    // and now we will submit a virtual thread that will be pinned periodically
    AtomicBoolean done = new AtomicBoolean(false);
    try (var service = Executors.newVirtualThreadPerTaskExecutor()) {
      Object monitor = new Object();
      service.submit(
          () -> {
            while (!done.get()) {
              // pin the thread
              synchronized (monitor) {
                LockSupport.parkNanos(30_000L);
              }
            }
          });
      waitFull(1_500);
      done.set(true);
    }

    assertTrue(metricSupport.metricNames.contains(JfrMetricsEmitter.JVM_VIRTUAL_THREAD_PINNED_KEY));
  }

  @Test
  void testTriggering() throws Exception {
    JfrMetricsEmitter.Triggering t = new JfrMetricsEmitter.Triggering(Duration.ofSeconds(1));
    AtomicInteger triggered = new AtomicInteger(0);
    long ts = System.currentTimeMillis();
    t.record(triggered::incrementAndGet);
    assertEquals(1, triggered.get());
    t.record(triggered::incrementAndGet);
    assumeTrue(System.currentTimeMillis() - ts < 950); // less than 1 second
    assertEquals(1, triggered.get()); // no re-trigger
    waitFull(1_100); // after 1 second the trigger is reset
    t.record(triggered::incrementAndGet);
    assumeTrue(System.currentTimeMillis() - ts < 2_050); // less than 2 seconds
    assertEquals(2, triggered.get()); // should be re-triggered
  }

  private static void waitFull(long ms) {
    long start = System.currentTimeMillis();
    long end = start + ms;
    while (System.currentTimeMillis() < end) {
      try {
        Thread.sleep(end - System.currentTimeMillis()); // deal with spurious wakeups
      } catch (InterruptedException e) {
        // ignore
      }
    }
  }
}
