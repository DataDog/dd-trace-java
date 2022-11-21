package datadog.telemetry;

import datadog.telemetry.api.Dependency;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.Metric;
import java.util.Map;

public interface TelemetryService {

  boolean addConfiguration(Map<String, Object> configuration);

  boolean addDependency(Dependency dependency);

  boolean addIntegration(Integration integration);

  boolean addMetric(Metric metric);

  RequestStatus sendAppStarted(RequestBuilder requestBuilder);

  RequestStatus sendTelemetry(RequestBuilder requestBuilder);

  RequestStatus sendAppClosing(RequestBuilder requestBuilder);
}
