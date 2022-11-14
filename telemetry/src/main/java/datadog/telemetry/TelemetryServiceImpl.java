package datadog.telemetry;

import datadog.telemetry.api.AppDependenciesLoaded;
import datadog.telemetry.api.AppIntegrationsChange;
import datadog.telemetry.api.AppStarted;
import datadog.telemetry.api.Dependency;
import datadog.telemetry.api.GenerateMetrics;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.KeyValue;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.Payload;
import datadog.telemetry.api.RequestType;
import datadog.trace.api.time.TimeSource;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryServiceImpl implements TelemetryService {

  private static final Logger log = LoggerFactory.getLogger(TelemetryServiceImpl.class);
  private final TimeSource timeSource;
  private final int heartbeatIntervalMs;
  private final BlockingQueue<KeyValue> configurations = new LinkedBlockingQueue<>();
  private final BlockingQueue<Integration> integrations = new LinkedBlockingQueue<>();
  private final BlockingQueue<Dependency> dependencies = new LinkedBlockingQueue<>();
  private final BlockingQueue<Metric> metrics =
      new LinkedBlockingQueue<>(1024); // recommended capacity?

  private final Queue<TelemetryData> queue = new ArrayBlockingQueue<>(16);

  private long lastPreparationTimestamp;

  public TelemetryServiceImpl(TimeSource timeSource, int heartBeatIntervalSec) {
    this.timeSource = timeSource;
    this.heartbeatIntervalMs = heartBeatIntervalSec * 1000; // we use time in milliseconds
  }

  @Override
  public void addStartedRequest() {
    Payload payload =
        new AppStarted()
            ._configuration(drainOrNull(configurations)) // absent if nothing
            .integrations(drainOrEmpty(integrations)) // empty list if nothing
            .dependencies(drainOrEmpty(dependencies)) // empty list if nothing
            .requestType(RequestType.APP_STARTED);
    TelemetryData request = new TelemetryData(RequestType.APP_STARTED, payload);
    queue.offer(request);
  }

  @Override
  public TelemetryData appClosingRequest() {
    return new TelemetryData(RequestType.APP_CLOSING, null);
  }

  @Override
  public boolean addConfiguration(Map<String, Object> configuration) {
    for (Map.Entry<String, Object> entry : configuration.entrySet()) {
      if (!this.configurations.offer(new KeyValue().name(entry.getKey()).value(entry.getValue()))) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean addDependency(Dependency dependency) {
    return this.dependencies.offer(dependency);
  }

  @Override
  public boolean addIntegration(Integration integration) {
    return this.integrations.offer(integration);
  }

  @Override
  public boolean addMetric(Metric metric) {
    return this.metrics.offer(metric);
  }

  @Override
  public Queue<TelemetryData> prepareRequests() {
    // New integrations
    if (!integrations.isEmpty()) {
      Payload payload = new AppIntegrationsChange().integrations(drainOrEmpty(integrations));
      TelemetryData request = new TelemetryData(RequestType.APP_INTEGRATIONS_CHANGE, payload);
      queue.offer(request);
    }

    // New dependencies
    if (!dependencies.isEmpty()) {
      Payload payload = new AppDependenciesLoaded().dependencies(drainOrEmpty(dependencies));
      TelemetryData request = new TelemetryData(RequestType.APP_DEPENDENCIES_LOADED, payload);
      queue.offer(request);
    }

    // New metrics
    if (!metrics.isEmpty()) {
      Payload payload =
          new GenerateMetrics()
              .namespace("appsec")
              .libLanguage("java")
              .libVersion("0.0.0")
              .series(drainOrEmpty(metrics));
      TelemetryData request = new TelemetryData(RequestType.GENERATE_METRICS, payload);
      queue.offer(request);
    }

    // Heartbeat request if needed
    long curTime = this.timeSource.getCurrentTimeMillis();
    if (!queue.isEmpty()) {
      lastPreparationTimestamp = curTime;
    }
    if (curTime - lastPreparationTimestamp > heartbeatIntervalMs) {
      lastPreparationTimestamp = curTime;
      TelemetryData request = new TelemetryData(RequestType.APP_HEARTBEAT, null);
      queue.offer(request);
    }

    return queue;
  }

  @Override
  public int getHeartbeatInterval() {
    return heartbeatIntervalMs;
  }

  private static <T> List<T> drainOrNull(BlockingQueue<T> srcQueue) {
    return drainOrDefault(srcQueue, null);
  }

  private static <T> List<T> drainOrEmpty(BlockingQueue<T> srcQueue) {
    return drainOrDefault(srcQueue, Collections.<T>emptyList());
  }

  private static <T> List<T> drainOrDefault(BlockingQueue<T> srcQueue, List<T> defaultList) {
    List<T> list = new LinkedList<>();
    int drained = srcQueue.drainTo(list);
    if (drained > 0) {
      return list;
    }
    return defaultList;
  }
}
