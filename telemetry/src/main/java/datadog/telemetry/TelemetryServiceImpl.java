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
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryServiceImpl implements TelemetryService {

  private static final int HEARTBEAT_INTERVAL_MS = 60 * 1000;

  private static final Logger log = LoggerFactory.getLogger(TelemetryServiceImpl.class);

  private final RequestBuilder requestBuilder;
  private final TimeSource timeSource;

  private final BlockingQueue<KeyValue> configurations = new LinkedBlockingQueue<>();
  private final BlockingQueue<Integration> integrations = new LinkedBlockingQueue<>();
  private final BlockingQueue<Dependency> dependencies = new LinkedBlockingQueue<>();
  private final BlockingQueue<Metric> metrics =
      new LinkedBlockingQueue<>(1024); // recommended capacity?

  private final Queue<Request> queue = new ArrayBlockingQueue<>(16);

  private long lastPreparationTimestamp;

  public TelemetryServiceImpl(RequestBuilder requestBuilder, TimeSource timeSource) {
    this.requestBuilder = requestBuilder;
    this.timeSource = timeSource;
  }

  @Override
  public void addStartedRequest() {
    Payload payload =
        new AppStarted()
            ._configuration(drainOrNull(configurations))
            .integrations(drainOrNull(integrations))
            .dependencies(drainOrNull(dependencies))
            .requestType(RequestType.APP_STARTED);

    queue.offer(requestBuilder.build(RequestType.APP_STARTED, payload));
  }

  @Override
  public Request appClosingRequest() {
    return requestBuilder.build(RequestType.APP_CLOSING);
  }

  @Override
  public boolean addConfiguration(KeyValue configuration) {
    return this.configurations.offer(configuration);
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

  Queue<Request> prepareRequests() {
    // New integrations
    if (!integrations.isEmpty()) {
      Payload payload = new AppIntegrationsChange().integrations(drainOrNull(integrations));
      Request request =
          requestBuilder.build(
              RequestType.APP_INTEGRATIONS_CHANGE,
              payload.requestType(RequestType.APP_INTEGRATIONS_CHANGE));
      queue.offer(request);
    }

    // New dependencies
    if (!dependencies.isEmpty()) {
      Payload payload = new AppDependenciesLoaded().dependencies(drainOrNull(dependencies));
      Request request =
          requestBuilder.build(
              RequestType.APP_DEPENDENCIES_LOADED,
              payload.requestType(RequestType.APP_DEPENDENCIES_LOADED));
      queue.offer(request);
    }

    // New metrics
    if (!metrics.isEmpty()) {
      Payload payload =
          new GenerateMetrics()
              .namespace("appsec")
              .libLanguage("java")
              .libVersion("0.0.0")
              .series(drainOrNull(metrics));
      Request request =
          requestBuilder.build(
              RequestType.GENERATE_METRICS, payload.requestType(RequestType.GENERATE_METRICS));
      queue.offer(request);
    }

    // Heartbeat request if needed
    long curTime = this.timeSource.getCurrentTimeMillis();
    if (!queue.isEmpty()) {
      lastPreparationTimestamp = curTime;
    }
    if (curTime - lastPreparationTimestamp > HEARTBEAT_INTERVAL_MS) {
      Request request = requestBuilder.build(RequestType.APP_HEARTBEAT);
      queue.offer(request);
      lastPreparationTimestamp = curTime;
    }

    return queue;
  }

  private static <T> List<T> drainOrNull(BlockingQueue<T> srcQueue) {
    List<T> list = new LinkedList<>();
    int drained = srcQueue.drainTo(list);
    if (drained > 0) {
      return list;
    }
    return null;
  }
}
