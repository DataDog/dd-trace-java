package datadog.telemetry;

import datadog.telemetry.api.ConfigChange;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.RequestType;
import datadog.telemetry.dependency.Dependency;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryService {
  private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);
  private static final String API_ENDPOINT = "telemetry/proxy/api/v2/apmtelemetry";

  private static final int MAX_ELEMENTS_PER_REQUEST = 100;

  // https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/producing-telemetry.md#when-to-use-it-1
  private static final int MAX_DEPENDENCIES_PER_REQUEST = 2000;

  private final HttpClient httpClient;
  private final int maxElementsPerReq;
  private final int maxDepsPerReq;
  private final BlockingQueue<ConfigChange> configurations = new LinkedBlockingQueue<>();
  private final BlockingQueue<Integration> integrations = new LinkedBlockingQueue<>();
  private final BlockingQueue<Dependency> dependencies = new LinkedBlockingQueue<>();
  private final BlockingQueue<Metric> metrics =
      new LinkedBlockingQueue<>(1024); // recommended capacity?

  private final BlockingQueue<LogMessage> logMessages = new LinkedBlockingQueue<>(1024);

  private final BlockingQueue<DistributionSeries> distributionSeries =
      new LinkedBlockingQueue<>(1024);

  private final HttpUrl httpUrl;

  private boolean sentAppStarted;

  /*
   * Keep track of Open Tracing and Open Telemetry integrations activation as they are mutually exclusive.
   */
  private boolean openTracingIntegrationEnabled;
  private boolean openTelemetryIntegrationEnabled;

  public TelemetryService(final OkHttpClient okHttpClient, final HttpUrl httpUrl) {
    this(new HttpClient(okHttpClient), httpUrl);
  }

  public TelemetryService(final HttpClient httpClient, final HttpUrl httpUrl) {
    this(httpClient, MAX_ELEMENTS_PER_REQUEST, MAX_DEPENDENCIES_PER_REQUEST, httpUrl);
  }

  // For testing purposes
  TelemetryService(
      final HttpClient httpClient,
      final int maxElementsPerReq,
      final int maxDepsPerReq,
      final HttpUrl agentUrl) {
    this.httpClient = httpClient;
    this.sentAppStarted = false;
    this.openTracingIntegrationEnabled = false;
    this.openTelemetryIntegrationEnabled = false;
    this.maxElementsPerReq = maxElementsPerReq;
    this.maxDepsPerReq = maxDepsPerReq;
    this.httpUrl = agentUrl.newBuilder().addPathSegments(API_ENDPOINT).build();
  }

  public boolean addConfiguration(Map<String, Object> configuration) {
    for (Map.Entry<String, Object> entry : configuration.entrySet()) {
      if (!this.configurations.offer(new ConfigChange(entry.getKey(), entry.getValue()))) {
        return false;
      }
    }
    return true;
  }

  public boolean addDependency(Dependency dependency) {
    return this.dependencies.offer(dependency);
  }

  public boolean addIntegration(Integration integration) {
    if ("opentelemetry-1".equals(integration.name) && integration.enabled) {
      this.openTelemetryIntegrationEnabled = true;
      warnAboutExclusiveIntegrations();
    } else if ("opentracing".equals(integration.name) && integration.enabled) {
      this.openTracingIntegrationEnabled = true;
      warnAboutExclusiveIntegrations();
    }
    return this.integrations.offer(integration);
  }

  public boolean addMetric(Metric metric) {
    return this.metrics.offer(metric);
  }

  public boolean addLogMessage(LogMessage message) {
    // TODO doesn't seem to be used
    return this.logMessages.offer(message);
  }

  public boolean addDistributionSeries(DistributionSeries series) {
    // TODO doesn't seem to be used
    return this.distributionSeries.offer(series);
  }

  public void sendAppClosingRequest() {
    RequestBuilder rb = new RequestBuilder(RequestType.APP_CLOSING, httpUrl);
    rb.writeHeader();
    rb.writeFooter();
    Request request = rb.request();
    httpClient.sendRequest(request);
  }

  public void sendIntervalRequests() {
    final State state =
        new State(
            configurations, integrations, dependencies, metrics, distributionSeries, logMessages);
    if (!sentAppStarted) {
      RequestBuilder rb = new RequestBuilder(RequestType.APP_STARTED, httpUrl);
      rb.writeHeader();
      rb.writeConfigChangeEvent(state.configurations.getOrNull());
      rb.writeIntegrationsEvent(state.integrations.get(maxElementsPerReq));
      rb.writeDependenciesLoadedEvent(state.dependencies.get(maxElementsPerReq));
      rb.writeFooter();
      HttpClient.Result result = httpClient.sendRequest(rb.request());

      if (result != HttpClient.Result.SUCCESS) {
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

    {
      RequestBuilder rb = new RequestBuilder(RequestType.APP_HEARTBEAT, httpUrl);
      rb.writeHeader();
      rb.writeFooter();
      if (httpClient.sendRequest(rb.request()) == HttpClient.Result.NOT_FOUND) {
        state.rollback();
        return;
      }
    }

    while (!state.configurations.isEmpty()) {
      RequestBuilder rb = new RequestBuilder(RequestType.APP_CLIENT_CONFIGURATION_CHANGE, httpUrl);
      rb.writeHeader();
      rb.writeConfigChangeEvent(state.configurations.get(maxElementsPerReq));
      rb.writeFooter();
      HttpClient.Result result = httpClient.sendRequest(rb.request());
      if (result == HttpClient.Result.SUCCESS) {
        state.commit();
      } else if (result == HttpClient.Result.NOT_FOUND) {
        state.rollback();
        return;
      } else {
        state.configurations.rollback();
        break;
      }
    }

    while (!state.integrations.isEmpty()) {
      RequestBuilder rb = new RequestBuilder(RequestType.APP_INTEGRATIONS_CHANGE, httpUrl);
      rb.writeHeader();
      rb.writeIntegrationsEvent(state.integrations.get(maxElementsPerReq));
      rb.writeFooter();
      HttpClient.Result result = httpClient.sendRequest(rb.request());
      if (result == HttpClient.Result.SUCCESS) {
        state.commit();
      } else if (result == HttpClient.Result.NOT_FOUND) {
        state.rollback();
        return;
      } else {
        state.integrations.rollback();
        break;
      }
    }

    while (!state.dependencies.isEmpty()) {
      RequestBuilder rb = new RequestBuilder(RequestType.APP_DEPENDENCIES_LOADED, httpUrl);
      rb.writeHeader();
      rb.writeDependenciesLoadedEvent(state.dependencies.get(maxDepsPerReq));
      rb.writeFooter();
      HttpClient.Result result = httpClient.sendRequest(rb.request());
      if (result == HttpClient.Result.SUCCESS) {
        state.commit();
      } else if (result == HttpClient.Result.NOT_FOUND) {
        state.rollback();
        return;
      } else {
        state.dependencies.rollback();
        break;
      }
    }

    while (!state.metrics.isEmpty()) {
      RequestBuilder rb = new RequestBuilder(RequestType.GENERATE_METRICS, httpUrl);
      rb.writeHeader();
      rb.writeMetrics(state.metrics.get(maxElementsPerReq));
      rb.writeFooter();
      HttpClient.Result result = httpClient.sendRequest(rb.request());
      if (result == HttpClient.Result.SUCCESS) {
        state.commit();
      } else if (result == HttpClient.Result.NOT_FOUND) {
        state.rollback();
        return;
      } else {
        state.metrics.rollback();
        break;
      }
    }

    while (!state.distributionSeries.isEmpty()) {
      RequestBuilder rb = new RequestBuilder(RequestType.DISTRIBUTIONS, httpUrl);
      rb.writeHeader();
      rb.writeDistributionsEvent(state.distributionSeries.get(maxElementsPerReq));
      rb.writeFooter();
      HttpClient.Result result = httpClient.sendRequest(rb.request());
      if (result == HttpClient.Result.SUCCESS) {
        state.commit();
      } else if (result == HttpClient.Result.NOT_FOUND) {
        state.rollback();
        return;
      } else {
        state.distributionSeries.rollback();
        break;
      }
    }

    while (!state.logMessages.isEmpty()) {

      RequestBuilder rb = new RequestBuilder(RequestType.LOGS, httpUrl);
      rb.writeHeader();
      rb.writeLogsEvent(state.logMessages.get(maxElementsPerReq));
      rb.writeFooter();
      HttpClient.Result result = httpClient.sendRequest(rb.request());

      if (result == HttpClient.Result.SUCCESS) {
        state.commit();
      } else if (result == HttpClient.Result.NOT_FOUND) {
        state.rollback();
        return;
      } else {
        state.logMessages.rollback();
        break;
      }
    }
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
    private final StateList<ConfigChange> configurations;
    private final StateList<Integration> integrations;
    private final StateList<Dependency> dependencies;
    private final StateList<Metric> metrics;
    private final StateList<DistributionSeries> distributionSeries;
    private final StateList<LogMessage> logMessages;

    public State(
        BlockingQueue<ConfigChange> configurations,
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
