package datadog.telemetry;

import datadog.telemetry.api.AppDependenciesLoaded;
import datadog.telemetry.api.AppIntegrationsChange;
import datadog.telemetry.api.AppStarted;
import datadog.telemetry.api.Dependency;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Distributions;
import datadog.telemetry.api.GenerateMetrics;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.KeyValue;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Logs;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.Payload;
import datadog.telemetry.api.RequestType;
import datadog.trace.api.time.TimeSource;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryServiceImpl implements TelemetryService {

  private static final Logger log = LoggerFactory.getLogger(TelemetryServiceImpl.class);
  private static final String TELEMETRY_NAMESPACE_TAG_TRACER = "tracers";

  private static final int MAX_ELEMENTS_PER_REQUEST = 100;

  // https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/producing-telemetry.md#when-to-use-it-1
  private static final int MAX_DEPENDENCIES_PER_REQUEST = 2000;

  private final Supplier<RequestBuilder> requestBuilderSupplier;
  private final TimeSource timeSource;
  private final int maxElementsPerReq;
  private final int maxDepsPerReq;
  private final int heartbeatIntervalMs;
  private final int metricsIntervalMs;
  private final BlockingQueue<KeyValue> configurations = new LinkedBlockingQueue<>();
  private final BlockingQueue<Integration> integrations = new LinkedBlockingQueue<>();
  private final BlockingQueue<Dependency> dependencies = new LinkedBlockingQueue<>();
  private final BlockingQueue<Metric> metrics =
      new LinkedBlockingQueue<>(1024); // recommended capacity?

  private final BlockingQueue<LogMessage> logMessages = new LinkedBlockingQueue<>(1024);

  private final BlockingQueue<DistributionSeries> distributionSeries =
      new LinkedBlockingQueue<>(1024);

  private final Queue<Request> queue = new ArrayBlockingQueue<>(16);

  private long lastPreparationTimestamp;
  /*
   * Keep track of Open Tracing and Open Telemetry integrations activation as they are mutually exclusive.
   */
  private boolean openTracingIntegrationEnabled;
  private boolean openTelemetryIntegrationEnabled;

  public TelemetryServiceImpl(
      Supplier<RequestBuilder> requestBuilderSupplier,
      TimeSource timeSource,
      int heartBeatIntervalSec,
      int metricsIntervalSec) {
    this(
        requestBuilderSupplier,
        timeSource,
        heartBeatIntervalSec,
        metricsIntervalSec,
        MAX_ELEMENTS_PER_REQUEST,
        MAX_DEPENDENCIES_PER_REQUEST);
  }

  // For testing purpose
  TelemetryServiceImpl(
      Supplier<RequestBuilder> requestBuilderSupplier,
      TimeSource timeSource,
      int heartBeatIntervalSec,
      int metricsIntervalSec,
      int maxElementsPerReq,
      int maxDepsPerReq) {
    this.requestBuilderSupplier = requestBuilderSupplier;
    this.timeSource = timeSource;
    this.heartbeatIntervalMs = heartBeatIntervalSec * 1000; // we use time in milliseconds
    this.metricsIntervalMs = metricsIntervalSec * 1000;
    this.openTracingIntegrationEnabled = false;
    this.openTelemetryIntegrationEnabled = false;
    this.maxElementsPerReq = maxElementsPerReq;
    this.maxDepsPerReq = maxDepsPerReq;
  }

  @Override
  public void addStartedRequest() {
    Payload payload =
        new AppStarted()
            ._configuration(drainOrNull(configurations)) // absent if nothing
            .integrations(drainOrEmpty(integrations, maxElementsPerReq)) // empty list if nothing
            .dependencies(drainOrEmpty(dependencies, maxDepsPerReq)) // empty list if nothing
            .requestType(RequestType.APP_STARTED);

    queue.offer(requestBuilderSupplier.get().build(RequestType.APP_STARTED, payload));
  }

  @Override
  public Request appClosingRequest() {
    return requestBuilderSupplier.get().build(RequestType.APP_CLOSING);
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
    if ("opentelemetry-1".equals(integration.getName())) {
      this.openTelemetryIntegrationEnabled = true;
      warnAboutExclusiveIntegrations();
    } else if ("opentracing".equals(integration.getName())) {
      this.openTracingIntegrationEnabled = true;
      warnAboutExclusiveIntegrations();
    }
    return this.integrations.offer(integration);
  }

  @Override
  public boolean addMetric(Metric metric) {
    return this.metrics.offer(metric);
  }

  @Override
  public boolean addLogMessage(LogMessage message) {
    return this.logMessages.offer(message);
  }

  @Override
  public boolean addDistributionSeries(DistributionSeries series) {
    return this.distributionSeries.offer(series);
  }

  @Override
  public Queue<Request> prepareRequests() {
    // New integrations
    while (!integrations.isEmpty()) {
      Payload payload =
          new AppIntegrationsChange().integrations(drainOrEmpty(integrations, maxElementsPerReq));
      Request request =
          requestBuilderSupplier
              .get()
              .build(
                  RequestType.APP_INTEGRATIONS_CHANGE,
                  payload.requestType(RequestType.APP_INTEGRATIONS_CHANGE));
      queue.offer(request);
    }

    // New dependencies
    while (!dependencies.isEmpty()) {
      Payload payload =
          new AppDependenciesLoaded().dependencies(drainOrEmpty(dependencies, maxDepsPerReq));
      Request request =
          requestBuilderSupplier
              .get()
              .build(
                  RequestType.APP_DEPENDENCIES_LOADED,
                  payload.requestType(RequestType.APP_DEPENDENCIES_LOADED));
      queue.offer(request);
    }

    // New metrics
    while (!metrics.isEmpty()) {
      Payload payload =
          new GenerateMetrics()
              .namespace(TELEMETRY_NAMESPACE_TAG_TRACER)
              .series(drainOrEmpty(metrics, maxElementsPerReq));
      Request request =
          requestBuilderSupplier
              .get()
              .build(
                  RequestType.GENERATE_METRICS, payload.requestType(RequestType.GENERATE_METRICS));
      queue.offer(request);
    }

    // New messages
    while (!logMessages.isEmpty()) {
      Payload payload = new Logs().messages(drainOrEmpty(logMessages, maxElementsPerReq));
      Request request =
          requestBuilderSupplier
              .get()
              .build(RequestType.LOGS, payload.requestType(RequestType.LOGS));
      queue.offer(request);
    }

    // New Distributions
    while (!distributionSeries.isEmpty()) {
      Payload payload =
          new Distributions()
              .namespace(TELEMETRY_NAMESPACE_TAG_TRACER)
              .series(drainOrEmpty(distributionSeries, maxElementsPerReq));
      Request request =
          requestBuilderSupplier
              .get()
              .build(RequestType.DISTRIBUTIONS, payload.requestType(RequestType.DISTRIBUTIONS));
      queue.offer(request);
    }

    // Heartbeat request if needed
    long curTime = this.timeSource.getCurrentTimeMillis();
    if (!queue.isEmpty()) {
      lastPreparationTimestamp = curTime;
    }
    if (curTime - lastPreparationTimestamp > heartbeatIntervalMs) {
      Request request = requestBuilderSupplier.get().build(RequestType.APP_HEARTBEAT);
      queue.offer(request);
      lastPreparationTimestamp = curTime;
    }

    return queue;
  }

  @Override
  public int getHeartbeatInterval() {
    return heartbeatIntervalMs;
  }

  @Override
  public int getMetricsInterval() {
    return metricsIntervalMs;
  }

  private void warnAboutExclusiveIntegrations() {
    if (this.openTelemetryIntegrationEnabled && this.openTracingIntegrationEnabled) {
      log.warn(
          "Both Open Tracing and Open Telemetry integrations are enabled but mutually exclusive. Tracing performance can be degraded.");
    }
  }

  private static <T> List<T> drainOrNull(BlockingQueue<T> srcQueue) {
    return drainOrDefault(srcQueue, null, Integer.MAX_VALUE);
  }

  private static <T> List<T> drainOrEmpty(BlockingQueue<T> srcQueue, int maxItems) {
    return drainOrDefault(srcQueue, Collections.<T>emptyList(), maxItems);
  }

  private static <T> List<T> drainOrDefault(
      BlockingQueue<T> srcQueue, List<T> defaultList, int maxItems) {
    List<T> list = new LinkedList<>();
    int drained = srcQueue.drainTo(list, maxItems);
    if (drained > 0) {
      return list;
    }
    return defaultList;
  }
}
