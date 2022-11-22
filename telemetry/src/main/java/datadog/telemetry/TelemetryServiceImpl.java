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
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetryServiceImpl implements TelemetryService {

  private static final Logger log = LoggerFactory.getLogger(TelemetryServiceImpl.class);
  private final TelemetryHttpClient httpClient;
  private final Set<KeyValue> configurations = new LimitedLinkedHashSet<>(1024);
  private final Set<Integration> integrations = new LimitedLinkedHashSet<>(1024);
  private final Set<Dependency> dependencies = new LimitedLinkedHashSet<>(1024);
  private final Set<Metric> metrics = new LimitedLinkedHashSet<>(1024);

  public TelemetryServiceImpl(TelemetryHttpClient httpClient) {
    this.httpClient = httpClient;
  }

  @Override
  public boolean addConfiguration(Map<String, Object> configuration) {
    for (Map.Entry<String, Object> entry : configuration.entrySet()) {
      if (!this.configurations.add(new KeyValue().name(entry.getKey()).value(entry.getValue()))) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean addDependency(Dependency dependency) {
    return this.dependencies.add(dependency);
  }

  @Override
  public boolean addIntegration(Integration integration) {
    return this.integrations.add(integration);
  }

  @Override
  public boolean addMetric(Metric metric) {
    return this.metrics.add(metric);
  }

  @Override
  public RequestStatus sendAppStarted(RequestBuilder requestBuilder) {

    if (requestBuilder == null) {
      return RequestStatus.ENDPOINT_ERROR;
    }

    List<KeyValue> configs = null;
    if (!configurations.isEmpty()) {
      configs = new LinkedList<>(configurations);
    }

    List<Integration> integs = Collections.emptyList();
    if (!integrations.isEmpty()) {
      integs = new LinkedList<>(integrations);
    }

    List<Dependency> deps = Collections.emptyList();
    if (!dependencies.isEmpty()) {
      deps = new LinkedList<>(dependencies);
    }

    Payload payload =
        new AppStarted()
            ._configuration(configs) // null if nothing
            .integrations(integs) // empty list if nothing
            .dependencies(deps) // empty list if nothing
            .requestType(RequestType.APP_STARTED);

    Request request = requestBuilder.build(RequestType.APP_STARTED, payload);
    RequestStatus status = httpClient.sendRequest(request);

    // Telemetry successfully sent - clear data
    if (status == RequestStatus.SUCCESS) {
      if (configs != null) {
        configurations.removeAll(configs);
      }
      integrations.removeAll(integs);
      dependencies.removeAll(deps);
    }

    return status;
  }

  RequestStatus sendIntegrations(RequestBuilder requestBuilder) {
    List<Integration> integs = new LinkedList<>(integrations);

    Payload payload =
        new AppIntegrationsChange()
            .integrations(integs)
            .requestType(RequestType.APP_INTEGRATIONS_CHANGE);

    Request request = requestBuilder.build(RequestType.APP_INTEGRATIONS_CHANGE, payload);
    RequestStatus status = httpClient.sendRequest(request);

    if (status == RequestStatus.SUCCESS) {
      integrations.removeAll(integs);
    }

    return status;
  }

  RequestStatus sendDependencies(RequestBuilder requestBuilder) {
    List<Dependency> deps = new LinkedList<>(dependencies);

    Payload payload =
        new AppDependenciesLoaded()
            .dependencies(deps)
            .requestType(RequestType.APP_DEPENDENCIES_LOADED);

    Request request = requestBuilder.build(RequestType.APP_DEPENDENCIES_LOADED, payload);
    RequestStatus status = httpClient.sendRequest(request);

    if (status == RequestStatus.SUCCESS) {
      dependencies.removeAll(deps);
    }

    return status;
  }

  RequestStatus sendMetrics(RequestBuilder requestBuilder) {
    List<Metric> mtrs = new LinkedList<>(metrics);

    Payload payload =
        new GenerateMetrics()
            .namespace("appsec")
            .libLanguage("java")
            .libVersion("0.0.0")
            .series(mtrs)
            .requestType(RequestType.GENERATE_METRICS);

    Request request = requestBuilder.build(RequestType.GENERATE_METRICS, payload);
    RequestStatus status = httpClient.sendRequest(request);

    if (status == RequestStatus.SUCCESS) {
      metrics.removeAll(mtrs);
    }

    return status;
  }

  @Override
  public RequestStatus sendTelemetry(RequestBuilder requestBuilder) {
    if (requestBuilder == null) {
      return RequestStatus.ENDPOINT_ERROR;
    }

    RequestStatus status;

    if (!integrations.isEmpty()) {
      status = sendIntegrations(requestBuilder);
      if (status != RequestStatus.SUCCESS) {
        return status;
      }
    }

    if (!dependencies.isEmpty()) {
      status = sendDependencies(requestBuilder);
      if (status != RequestStatus.SUCCESS) {
        return status;
      }
    }

    if (!metrics.isEmpty()) {
      status = sendMetrics(requestBuilder);
      if (status != RequestStatus.SUCCESS) {
        return status;
      }
    }

    return sendHeartbeat(requestBuilder);
  }

  @Override
  public RequestStatus sendAppClosing(RequestBuilder requestBuilder) {
    Request request = requestBuilder.build(RequestType.APP_CLOSING);
    return httpClient.sendRequest(request);
  }

  public RequestStatus sendHeartbeat(RequestBuilder requestBuilder) {
    Request request = requestBuilder.build(RequestType.APP_HEARTBEAT);
    return httpClient.sendRequest(request);
  }
}
