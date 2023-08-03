package datadog.telemetry;

import datadog.telemetry.api.AppClientConfigurationChange;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryService {

  private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);
  private static final String TELEMETRY_NAMESPACE_TAG_TRACER = "tracers";

  private static final int MAX_ELEMENTS_PER_REQUEST = 100;

  // https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/producing-telemetry.md#when-to-use-it-1
  private static final int MAX_DEPENDENCIES_PER_REQUEST = 2000;

  private final OkHttpClient httpClient;
  private final Supplier<RequestBuilder> requestBuilderSupplier;
  private final int maxElementsPerReq;
  private final int maxDepsPerReq;
  private final BlockingQueue<KeyValue> configurations = new LinkedBlockingQueue<>();
  private final BlockingQueue<Integration> integrations = new LinkedBlockingQueue<>();
  private final BlockingQueue<Dependency> dependencies = new LinkedBlockingQueue<>();
  private final BlockingQueue<Metric> metrics =
      new LinkedBlockingQueue<>(1024); // recommended capacity?

  private final BlockingQueue<LogMessage> logMessages = new LinkedBlockingQueue<>(1024);

  private final BlockingQueue<DistributionSeries> distributionSeries =
      new LinkedBlockingQueue<>(1024);

  private boolean sentAppStarted;

  /*
   * Keep track of Open Tracing and Open Telemetry integrations activation as they are mutually exclusive.
   */
  private boolean openTracingIntegrationEnabled;
  private boolean openTelemetryIntegrationEnabled;

  public TelemetryService(
      final OkHttpClient httpClient, final Supplier<RequestBuilder> requestBuilderSupplier) {
    this(
        httpClient, requestBuilderSupplier, MAX_ELEMENTS_PER_REQUEST, MAX_DEPENDENCIES_PER_REQUEST);
  }

  // For testing purposes
  TelemetryService(
      final OkHttpClient httpClient,
      final Supplier<RequestBuilder> requestBuilderSupplier,
      final int maxElementsPerReq,
      final int maxDepsPerReq) {
    this.httpClient = httpClient;
    this.requestBuilderSupplier = requestBuilderSupplier;
    this.sentAppStarted = false;
    this.openTracingIntegrationEnabled = false;
    this.openTelemetryIntegrationEnabled = false;
    this.maxElementsPerReq = maxElementsPerReq;
    this.maxDepsPerReq = maxDepsPerReq;
  }

  public boolean addConfiguration(Map<String, Object> configuration) {
    for (Map.Entry<String, Object> entry : configuration.entrySet()) {
      if (!this.configurations.offer(new KeyValue().name(entry.getKey()).value(entry.getValue()))) {
        return false;
      }
    }
    return true;
  }

  public boolean addDependency(Dependency dependency) {
    return this.dependencies.offer(dependency);
  }

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

  public boolean addMetric(Metric metric) {
    return this.metrics.offer(metric);
  }

  public boolean addLogMessage(LogMessage message) {
    return this.logMessages.offer(message);
  }

  public boolean addDistributionSeries(DistributionSeries series) {
    return this.distributionSeries.offer(series);
  }

  public void sendAppClosingRequest() {
    sendRequest(RequestType.APP_CLOSING, null);
  }

  public void sendIntervalRequests() {
    final State state =
        new State(
            configurations, integrations, dependencies, metrics, distributionSeries, logMessages);
    if (!sentAppStarted) {
      final Payload payload =
          new AppStarted()
              .configuration(state.configurations.getOrNull()) // absent if nothing
              .integrations(state.integrations.get(maxElementsPerReq)) // empty list if nothing
              .dependencies(state.dependencies.get(maxDepsPerReq)) // empty list if nothing
              .requestType(RequestType.APP_STARTED);
      if (sendRequest(RequestType.APP_STARTED, payload) != SendResult.SUCCESS) {
        // Do not send other telemetry messages unless app-started has been sent successfully.
        state.rollback();
        return;
      }
      sentAppStarted = true;
      state.commit();
      state.rollback();
      // When app-started is sent, we do not send more messages until the next interval.
      return;
    }

    if (sendRequest(RequestType.APP_HEARTBEAT, null) == SendResult.NOT_FOUND) {
      state.rollback();
      return;
    }

    while (!state.configurations.isEmpty()) {
      final Payload payload =
          new AppClientConfigurationChange()
              .configuration(state.configurations.get(maxElementsPerReq))
              .requestType(RequestType.APP_CLIENT_CONFIGURATION_CHANGE);
      final SendResult result = sendRequest(RequestType.APP_CLIENT_CONFIGURATION_CHANGE, payload);
      if (result == SendResult.SUCCESS) {
        state.commit();
      } else if (result == SendResult.NOT_FOUND) {
        state.rollback();
        return;
      } else {
        state.configurations.rollback();
        break;
      }
    }

    while (!state.integrations.isEmpty()) {
      final Payload payload =
          new AppIntegrationsChange()
              .integrations(state.integrations.get(maxElementsPerReq))
              .requestType(RequestType.APP_INTEGRATIONS_CHANGE);
      final SendResult result = sendRequest(RequestType.APP_INTEGRATIONS_CHANGE, payload);
      if (result == SendResult.SUCCESS) {
        state.commit();
      } else if (result == SendResult.NOT_FOUND) {
        state.rollback();
        return;
      } else {
        state.integrations.rollback();
        break;
      }
    }

    while (!state.dependencies.isEmpty()) {
      final Payload payload =
          new AppDependenciesLoaded()
              .dependencies(state.dependencies.get(maxDepsPerReq))
              .requestType(RequestType.APP_DEPENDENCIES_LOADED);
      final SendResult result = sendRequest(RequestType.APP_DEPENDENCIES_LOADED, payload);
      if (result == SendResult.SUCCESS) {
        state.commit();
      } else if (result == SendResult.NOT_FOUND) {
        state.rollback();
        return;
      } else {
        state.integrations.rollback();
        break;
      }
    }

    while (!state.metrics.isEmpty()) {
      final Payload payload =
          new GenerateMetrics()
              .namespace(TELEMETRY_NAMESPACE_TAG_TRACER)
              .series(state.metrics.get(maxElementsPerReq))
              .requestType(RequestType.GENERATE_METRICS);
      final SendResult result = sendRequest(RequestType.GENERATE_METRICS, payload);
      if (result == SendResult.SUCCESS) {
        state.commit();
      } else if (result == SendResult.NOT_FOUND) {
        state.rollback();
        return;
      } else {
        state.metrics.rollback();
        break;
      }
    }

    while (!state.distributionSeries.isEmpty()) {
      final Payload payload =
          new Distributions()
              .namespace(TELEMETRY_NAMESPACE_TAG_TRACER)
              .series(state.distributionSeries.get(maxElementsPerReq))
              .requestType(RequestType.DISTRIBUTIONS);
      final SendResult result = sendRequest(RequestType.DISTRIBUTIONS, payload);
      if (result == SendResult.SUCCESS) {
        state.commit();
      } else if (result == SendResult.NOT_FOUND) {
        state.rollback();
        return;
      } else {
        state.distributionSeries.rollback();
        break;
      }
    }

    while (!state.logMessages.isEmpty()) {
      final Payload payload =
          new Logs()
              .messages(state.logMessages.get(maxElementsPerReq))
              .requestType(RequestType.LOGS);
      final SendResult result = sendRequest(RequestType.LOGS, payload);
      if (result == SendResult.SUCCESS) {
        state.commit();
      } else if (result == SendResult.NOT_FOUND) {
        state.rollback();
        return;
      } else {
        state.logMessages.rollback();
        break;
      }
    }
  }

  private SendResult sendRequest(final RequestType type, final Payload payload) {
    final Request request = requestBuilderSupplier.get().build(type, payload);
    try (Response response = httpClient.newCall(request).execute()) {
      if (response.code() == 404) {
        log.debug("Telemetry endpoint is disabled, dropping {} message", type);
        return SendResult.NOT_FOUND;
      }
      if (!response.isSuccessful()) {
        log.debug(
            "Telemetry message {} failed with: {} {} ", type, response.code(), response.message());
        return SendResult.FAILURE;
      }
    } catch (IOException e) {
      log.debug("Telemetry message {} failed with exception: {}", type, e.toString());
      return SendResult.FAILURE;
    }

    log.debug("Telemetry message {} sent successfully", type);
    return SendResult.SUCCESS;
  }

  enum SendResult {
    SUCCESS,
    FAILURE,
    NOT_FOUND
  }

  private void warnAboutExclusiveIntegrations() {
    if (this.openTelemetryIntegrationEnabled && this.openTracingIntegrationEnabled) {
      log.warn(
          "Both Open Tracing and Open Telemetry integrations are enabled but mutually exclusive. Tracing performance can be degraded.");
    }
  }

  private static class StateList<T> {
    private final BlockingQueue<T> queue;
    private List<T> batch;
    private int consumed;

    public StateList(final BlockingQueue<T> queue) {
      this.queue = queue;
      final int size = queue.size();
      this.batch = new ArrayList<>(size);
      queue.drainTo(this.batch);
      this.consumed = 0;
    }

    public boolean isEmpty() {
      return consumed >= batch.size();
    }

    @Nullable
    public List<T> getOrNull() {
      final List<T> result = get();
      if (result.isEmpty()) {
        return null;
      }
      return result;
    }

    public List<T> get() {
      return get(batch.size());
    }

    public List<T> get(final int maxSize) {
      if (consumed >= batch.size()) {
        return Collections.emptyList();
      }
      final int toIndex = Math.min(batch.size(), consumed + maxSize);
      final List<T> result = batch.subList(consumed, toIndex);
      consumed += result.size();
      return result;
    }

    public void commit() {
      if (consumed >= batch.size()) {
        batch = Collections.emptyList();
      } else {
        batch = batch.subList(consumed, batch.size());
      }
      consumed = 0;
    }

    public void rollback() {
      for (final T element : batch) {
        // Ignore result, if the queue is full, we'll just lose data.
        // TODO: Emit a metric when data is lost.
        queue.offer(element);
      }
      batch = Collections.emptyList();
      consumed = 0;
    }
  }

  private static class State {
    private final StateList<KeyValue> configurations;
    private final StateList<Integration> integrations;
    private final StateList<Dependency> dependencies;
    private final StateList<Metric> metrics;
    private final StateList<DistributionSeries> distributionSeries;
    private final StateList<LogMessage> logMessages;

    public State(
        BlockingQueue<KeyValue> configurations,
        BlockingQueue<Integration> integrations,
        BlockingQueue<Dependency> dependencies,
        BlockingQueue<Metric> metrics,
        BlockingQueue<DistributionSeries> distributionSeries,
        BlockingQueue<LogMessage> logMessages) {
      this.configurations = new StateList<>(configurations);
      this.integrations = new StateList<>(integrations);
      this.dependencies = new StateList<>(dependencies);
      this.metrics = new StateList<>(metrics);
      this.distributionSeries = new StateList<>(distributionSeries);
      this.logMessages = new StateList<>(logMessages);
    }

    public void rollback() {
      this.configurations.rollback();
      this.integrations.rollback();
      this.dependencies.rollback();
      this.metrics.rollback();
      this.distributionSeries.rollback();
      this.logMessages.rollback();
    }

    public void commit() {
      this.configurations.commit();
      this.integrations.commit();
      this.dependencies.commit();
      this.metrics.commit();
      this.distributionSeries.commit();
      this.logMessages.commit();
    }
  }
}
