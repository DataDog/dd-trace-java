package datadog.telemetry;

import datadog.telemetry.api.Dependency;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.KeyValue;
import datadog.telemetry.api.Metric;
import okhttp3.Request;

public interface TelemetryService {

  // Special telemetry requests
  void addStartedRequest();

  Request appClosingRequest();

  // Data for periodic telemetry requests
  boolean addConfiguration(KeyValue configuration);

  boolean addDependency(Dependency dependency);

  boolean addIntegration(Integration integration);

  boolean addMetric(Metric metric);
}
